# 按用量计费的订阅计费服务（本地可运行）

一套基于 Spring Boot 3 / Java 21 的**订阅 + 用量**计费服务，支付通道为**本地模拟**，
数据全部在内存中，**不依赖外部支付服务、数据库、真实凭据或其他不可访问资源**，
`mvn test` / `mvn spring-boot:run` 即可在本地完整跑通并验证。

## 1. 计费模型

### 1.1 套餐（Plan）
- `basePrice`：每周期基础费（预付）
- `includedUnits`：周期内 `api_calls` 配额
- `overagePrice`：超出配额部分的单价
- 启动预置两档：
  - `BASIC`：30.00 / 1000 次 / 超出 0.05/次
  - `PRO`：80.00 / 5000 次 / 超出 0.02/次

### 1.2 周期（BillingCalendar）
- 以订阅锚点日 `anchorDay`（仅允许 **1–28**，避免月末日期缺失带来不确定结论）切分月度周期；
- 周期为 `[锚点日 00:00 UTC, 下一锚点日 00:00 UTC)`，因此 28/30/31 天的月份、跨月末、闰年都自然落在比例分母里，无需特判；
- 比例按**秒**精确计算并保留 6 位小数（`HALF_UP`）：
  `剩余比例 = (periodEnd - effectiveAt) / (periodEnd - periodStart)`；
- 金额统一 2 位小数、`HALF_UP`（见 `service/Money.java`）。

### 1.3 费用由三类账单明细构成（互不重复计价）
| 明细 `LineKind` | 含义 | 何时产生 |
|---|---|---|
| `BASE` | 周期基础费（预付） | 开通首期；每次周期出账时预收下一周期 |
| `PRORATION` | 中途升降配/退订的按比例差价 | 变更立即出一张差价账单 |
| `OVERAGE` | 本周期超配额费 `max(0, 用量-折算配额)×单价` | 周期出账（暂停过的周期配额按活跃时长折算） |
| `ADJUSTMENT` | 关账后迟到用量补计 / 缺计量人工确认 / 整周期暂停 0 元说明 | 迟到事件追加、人工确认零用量、整周期暂停滚动 |
| `PAUSE_PRORATION` | 客户主动暂停退回 / 恢复补收的按比例基础费 | `/pause`（负额退款）/`/resume`（正额补收），独立账单 |

- 周期账单 `金额 = OVERAGE(上一周期) + BASE(下一周期)`；中途差价另立 `PRORATION` 账单，升级补收、降级/退订退回，**绝不与周期账单重复计基础费**。
- 中途变更差价：`(新基础价 - 旧基础价) × 剩余比例`；退订：`-旧基础价 × 剩余比例`。
- 例：9/1（30 天周期）升级 BASIC(30)→PRO(80)，9/15 00:00 生效时剩余 16 天，
  补收 `50 × 16/30 = 26.67`。

### 1.4 三方账户与守恒（LedgerService）
所有资金流动都走**复式记账**，账户有三个：`SUBSCRIBER`（客户现金）、`PLATFORM`（平台收入）、`CHANNEL`（通道手续费，费率 1%）。
- 收款分录：`SUBSCRIBER -amount`、`PLATFORM +(amount-fee)`、`CHANNEL +fee`，三者和为 0；
- 退款以同一费率**反向冲回**；
- 任何事务借贷必须平衡，否则直接拒绝落账；
- 不变量：**任意时刻 `SUBSCRIBER + PLATFORM + CHANNEL = 0`**，
  由 `GET /api/admin/reconcile` 的 `conservationDelta` 与测试断言守护，补缴/退款前后均守恒。

## 2. 订阅与账单状态流转

### 2.1 订阅 SubscriptionStatus
```
ACTIVE ──周期账单扣款失败──▶ PAST_DUE(宽限中) ──超过宽限仍未付──▶ SUSPENDED
  ▲                              │                                     │
  └──────────── 任意未付节点补缴成功（恢复 ACTIVE 并滚动周期）◀──────────┘

ACTIVE ──客户主动暂停 /pause──▶ PAUSED ──客户恢复 /resume──▶ ACTIVE   （与上面的收款失败链路完全独立）
ACTIVE/PAUSED/PAST_DUE ──退订──▶ CANCELLED（终态）
```
- **两类“停用”严格区分，错误码可辨**：
  - `PAUSED` 是**客户主动**暂停：可 `/resume` 回来接着用，错误码为 `SUBSCRIPTION_PAUSED_BY_CUSTOMER`；
  - `SUSPENDED` 是**收款失败**被动停用：只能补缴 `repay` 恢复，错误码为 `SUBSCRIPTION_SUSPENDED`。
  - 二者互不相干：暂停期 `tick` 不驱动收款状态机；补缴/任何收款都不会把 `PAUSED` 滚成 `ACTIVE`。
- `PAUSED` 期间：拒绝升降配（`SUBSCRIPTION_PAUSED_BY_CUSTOMER`，需先恢复）、拒绝暂停账段内的用量上报
  （`USAGE_DURING_PAUSE`，独立可区分原因，既不当零也不记到别的周期）；
  暂停态退订允许，但暂停时已退过剩余基础费，**退订不再二次退款**（留 0 元退订痕迹）。
- `CANCELLED` 不再接受用量上报与变更。

### 2.2 账单 InvoiceStatus
```
ANOMALY ──补报 / 人工确认零用量(留 operator)──▶ PENDING
PENDING ──扣款失败──▶ FAILED ──(>3 天)──▶ PAST_DUE(宽限 7 天) ──(超过 dueAt)──▶ 订阅 SUSPENDED
PENDING/FAILED/PAST_DUE ──扣款成功──▶ PAID
负额差价账单 / 客户暂停退款账单 ──通道退款成功──▶ REFUNDED
0 元周期账单（整周期暂停/无超量且暂停态不预收基础费）──▶ PAID（无资金流水、直接滚动）
```
- **重试窗口**：首次失败（`failedAt` 固定，重试不刷新）起 3 天内，`tick` 自动重试；
- **宽限**：超过 3 天进 `PAST_DUE`，`dueAt = 当时 + 7 天`，期内 tick 仍尝试扣款；
- **暂停（收款失败）**：超过宽限仍失败，订阅 `SUSPENDED`；之后通道恢复调用 `repay` 补缴即可；
- 账单的每次通道交互都落 `PaymentAttempt`，非零 `PAID` 账单**恰好一笔成功收款**，不重复、不漏扣；
  0 元 `PAID` 账单**不应有任何**收款流水（对账会核）。

## 2A. 客户主动暂停 / 恢复（本次新增）

### 2A.1 计费口径（按秒比例，6 位小数、金额 HALF_UP 到分）
设周期为 `[periodStart, periodEnd)`，`remainingRatio(t) = (periodEnd - t)/(periodEnd - periodStart)`。

- **暂停 `/pause`（仅 ACTIVE 可发起，eff 在当前周期内）**
  - 从 `eff` 起不再产生基础费；立即按“当前套餐”退回 **eff 到周期末已预付但未使用** 的基础费：
    `退款 = -basePrice × remainingRatio(eff)`，出一张独立的 `PAUSE_PRORATION` 负额账单并走通道退款，订阅转 `PAUSED`；
  - 该账单与升降配差价账单、周期账单互不重复计基础费。
- **恢复 `/resume`（仅 PAUSED 可发起，eff 在当前周期内）**
  - 从 `eff` 到周期末按比例**重新补收**基础费：`补收 = +basePrice × remainingRatio(eff)`，订阅转 `ACTIVE`；
  - **本周期配额按真正活跃时长等比例折算**：
    `折算配额 = round_half_up(includedUnits × 活跃秒数 / 周期总秒数)`，暂停掉的时间不算在内；
  - 恢复后用量在原累计结果上**继续累加**，不丢量、不重复（仍由 `eventId` 去重）。
- **活跃时长与暂停账段**：PAUSE 与紧随的 RESUME 构成半开半闭账段 `[pauseAt, resumeAt)`；
  跨周期仍暂停的尾部账段以周期末收口。出账、对账、用量上报判定共用 `PauseTimeline` 同一口径，可手算。
- **暂停期间补报暂停前用量**：`usageAt` 落在暂停起点**之前**（真实活跃账段）的迟到上报**照常累计计费**，
  暂停状态不吞量；只有 `usageAt` 落在暂停账段 `[pauseAt,resumeAt)` 内才用 `USAGE_DURING_PAUSE` 拒绝。
- **整周期暂停**：周期活跃秒数 = 0 时，不出基础费、不出超配额费，出一张 0 元周期账单直接 `PAID` 并滚动，
  订阅保持 `PAUSED`；**无用量是暂停的确定结果，绝不判缺计量 ANOMALY**。
- **跨周期仍暂停**：每个周期结算与对账照常进行（0 元滚动），状态保持 `PAUSED`，随时可 `/resume`。

### 2A.2 守恒护栏
- 所有退款（暂停退款、升降配/退订差价退款、手工退款）在落账前统一校验：
  **该订阅成功退款累计 + 本次退款 ≤ 成功收款累计**，超出直接 `REFUND_LIMIT_EXCEEDED` 拒绝，不留半截状态；
- 退款与收款共用 1% 费率反向冲回，三方账户 `SUBSCRIBER + PLATFORM + CHANNEL = 0` 始终成立；
  对账额外逐订阅检查“退款累计不超过收款累计”，重复/超额退款会被发现并拦住。

### 2A.3 确定性与幂等
- 暂停/恢复与升降配/退订**共用订阅级 `seq` 全序**，同一把订阅锁串行：
  同一时刻并发的暂停、恢复、升降配、出账、收款只有**一个生效**，其余按 `CHANGE_OUT_OF_ORDER`
  /`SUBSCRIPTION_PAUSED_BY_CUSTOMER`/`CONFLICT` 等可区分原因拒绝，不出现退了又扣、状态来回跳；
- `effectiveAt` **不晚于**任一既有升降配/暂停/恢复即判乱序拒绝（同一时刻只有一个生效）；
- 暂停/恢复携带 `idempotencyKey`，且与升降配键空间**互斥唯一**；重复提交（即使改了参数）返回首次结果，
  不重复退款/扣款；通道失败时仅留账单草稿、不推进状态，同键重试安全。

## 3. 确定性、幂等与并发规则

### 3.1 变更的确定性
- 每个订阅一把分段锁（`SubscriptionLocks`），同一订阅的变更/暂停/恢复/上报/出账/收款**串行**，不同订阅互不阻塞；
- 每次变更/暂停/恢复共用单调递增的 `seq` 形成**全序**：同一时刻到达的多个操作严格按接收顺序处理，只有一次生效，其余被可解释地拒绝，**无中间态残留**；
- `effectiveAt` 不晚于上一次升降配/暂停/恢复的请求判 `CHANGE_OUT_OF_ORDER` 拒绝（乱序不覆盖，同一时刻只放行一个）；
- 变更/暂停/恢复先出账单草稿再调通道：**通道失败则状态不推进**（仅留可重试的账单状态），用同一 `idempotencyKey` 重试安全。

### 3.2 幂等键
| 操作 | 幂等标识 | 重复请求行为 |
|---|---|---|
| 变更/退订 | `idempotencyKey` | 返回原 `PlanChange`，不二次计价/退款 |
| **客户暂停/恢复** | `idempotencyKey`（与变更键空间互斥） | 返回原 `PauseRecord`，不二次退款/扣款 |
| 用量上报 | `eventId` | 返回既有累计，不加量；内容冲突则 `DUPLICATE_REQUEST`；落在暂停账段则 `USAGE_DURING_PAUSE` |
| 收款/补缴/退款/记账 | `idempotencyKey` | 返回原 `PaymentAttempt`/原事务，通道“先成功后超时”也不重复扣款；超额退款 `REFUND_LIMIT_EXCEEDED` |

### 3.3 用量累计
- 全局 `eventId` 去重（先判重落事件）+ 订阅锁内累计，`16` 线程并发下同一周期结果唯一确定；
- 累计行有单调 `version`；事件集合 `eventIds` 去重，**重复不加、乱序不丢**；
- **暂停账段拒收**：`usageAt` 落在客户暂停账段 `[pauseAt,resumeAt)` 内直接 `USAGE_DURING_PAUSE`
  明确拒绝，不当零、不串周期；`usageAt` 在暂停之前的真实用量即使暂停期间才补报也正常累计；
- **迟到不丢量**：`usageAt` 落在已关账周期照样累计并标记 `lateEventIds`（暂停前的活跃账段同理）；
  - 原账单未付 → 调整行并入账单重算金额；
  - 原账单已付 → 原账单金额不动，**另立 `ADJUSTMENT` 账单**即时补收；
  - 重算超配额时配额同样按该周期**活跃时长折算**，与出账口径一致；
- **缺计量不按零/上次值处理**：周期内**存在活跃账段**却没有任何 `api_calls` 上报时，周期账单置 `ANOMALY`
  （金额 0、不收款、不滚周期），必须补报或由人工 `confirm-zero`（留 `operator` 痕迹）后才继续；
  **整周期暂停（活跃秒数=0）不算缺计量**；显式上报 `quantity=0` 属于“已计量”，不是缺失。

## 4. 对账规则（ReconciliationService）
`GET /api/admin/reconcile` 逐张账单、逐订阅与全局检查：
1. 账单金额 = 明细行之和；
2. 周期账单（有活跃时长时）：`OVERAGE + 同周期 ADJ 调整 = max(0, 周期累计用量 - 折算配额) × 单价`，
   其中**折算配额 = 套餐配额 × 活跃秒数/周期秒数**（暂停时间剔除），账单与用量汇总对得上；
3. **整周期暂停**（活跃秒数=0）应为 0 元滚动；有活跃账段却缺计量且无“人工确认零用量”留痕却已计价 → 报问题；
4. 非零 `PAID` 账单恰好 1 笔成功收款；0 元 `PAID` 账单 0 笔收款；`REFUNDED` 负额账单恰好 1 笔成功退款；
5. **逐订阅：成功退款累计 ≤ 成功收款累计**（暂停/差价/手工退款共用上限），超出报 `REFUND_CAP:*`；
6. 每笔记账事务借贷平衡；三方账户余额之和为 0。
返回空 `issues` 即账实相符。

## 5. 本地验证方法

### 5.1 运行测试（含日志打印“输入 + 判定依据”）
```bash
mvn test
```
覆盖（共 25 个用例，原 15 个全部保留跑通，新增 10 个）：
- `HappyFlowIntegrationTest`：开通→上报→出账→收款→滚周期→对账守恒；
- `ConcurrentUsageReportTest`：**并发重复上报**（16 线程、同 eventId 并发）、迟到/乱序不丢不重；
- `MidCyclePlanChangeTest`：**周期中途升降配**（按秒比例可手算）、同时刻并发变更全序、乱序拒绝、同键重放、退订退款；
- `PaymentFailureRetryTest`：**收款失败→重试→宽限→停用→补缴恢复**，全程仅 1 笔成功扣款、三方守恒；
- `MissingMeteringTest`：缺计量 ANOMALY、异常账单禁收、人工确认留痕、显式 0 不算缺失；
- `LateUsageAdjustmentTest`：已付账单迟到用量另立调整账单、重复追加拒绝、对账平；
- `ErrorClassificationTest` / `BillingCalendarTest`：错误码可区分（含客户暂停 vs 收款停用 vs 暂停期上报）、跨月末/闰年周期边界；
- **`PauseResumeBillingTest`（新增 5 个）**：
  1. 周期中途暂停按剩余比例退预付基础费（-30×16/30 可手算）+ 同键不重复退款；
  2. 恢复按剩余比例补收、配额按活跃时长折算（20/30）、恢复后用量在原累计上继续累加不丢不重；
  3. 暂停期间/落在暂停窗内的用量以 `USAGE_DURING_PAUSE` 拒绝，被拒事件不入累计；
  4. 暂停期间补报暂停前（活跃账段）真实用量照常累计、按折算配额正常计费；
  5. 暂停跨整周期不出基础费、不误判 ANOMALY、0 元滚动且保持 PAUSED；
- **`PauseResumeConcurrencyTest`（新增 4 个）**：同一时刻并发暂停/恢复只 1 个生效、暂停 vs 升降配全序、
  暂停/恢复幂等重放与乱序拒绝、暂停期 tick/出账串行状态确定；
- **`PauseRefundGuardTest`（新增 1 个）**：暂停退款后再超额退款被 `REFUND_LIMIT_EXCEEDED` 拦截、恢复补收后三方守恒。
日志在 INFO 级别打印每次判定的输入与依据（如用量/配额/活跃比例/折算配额/费率/通道流水）。

### 5.2 启动服务并用 HTTP 走一遍
```bash
mvn spring-boot:run            # 或 mvn package && java -jar target/*.jar
# 服务固定监听 http://localhost:8080
```
```bash
# 1) 开通（首期基础费立即由模拟通道收取）
curl -s -XPOST localhost:8080/api/subscriptions -H 'Content-Type: application/json' \
  -d '{"subscriptionId":"s1","customerId":"c1","planId":"BASIC","anchorDay":1}'

# 2) 周期中途升级（携带幂等键；差价立即按比例结算）
curl -s -XPOST localhost:8080/api/subscriptions/s1/change -H 'Content-Type: application/json' \
  -d '{"targetPlanId":"PRO","idempotencyKey":"k1","effectiveAt":"2026-09-15T00:00:00Z"}'

# 3) 用量上报（eventId 幂等；重复提交结果一致）
curl -s -XPOST localhost:8080/api/usage -H 'Content-Type: application/json' \
  -d '{"eventId":"e1","subscriptionId":"s1","metric":"api_calls","quantity":5200,"usageAt":"2026-09-20T00:00:00Z"}'

# 4) 拨动时钟到周期末（服务内时钟可测，默认从 2026-09-01 起）
curl -s -XPOST localhost:8080/api/admin/clock/advance -H 'Content-Type: application/json' \
  -d '{"to":"2026-10-01T00:00:00Z"}'

# 5) 注入通道失败后出账（返回 402 GATEWAY_REJECTED，账单 FAILED、订阅 PAST_DUE）
curl -s -XPOST localhost:8080/api/admin/gateway/fail-next -H 'Content-Type: application/json' -d '{"times":1}'
curl -s -XPOST localhost:8080/api/billing/subscriptions/s1/close-period

# 6) tick 推进状态机（重试/宽限/暂停）；通道恢复后补缴
curl -s -XPOST localhost:8080/api/billing/subscriptions/s1/tick
curl -s -XPOST localhost:8080/api/payments/invoices/INV-s1-CYC-2026-09/repay \
  -H 'Content-Type: application/json' -d '{"idempotencyKey":"repay-1"}'

# 7) 缺计量：无任何上报直接出账 -> ANOMALY；人工确认零用量
curl -s -XPOST localhost:8080/api/billing/invoices/INV-.../confirm-zero \
  -H 'Content-Type: application/json' -d '{"operator":"ops-bob"}'

# 8) 对账与总账（issues 为空、conservationDelta=0 即守恒）
curl -s localhost:8080/api/admin/reconcile
curl -s localhost:8080/api/admin/ledger
```

### 5.2.1 客户主动暂停 / 恢复走一遍
```bash
# 新开一条订阅（首期 30 已收）
curl -s -XPOST localhost:8080/api/subscriptions -H 'Content-Type: application/json' \
  -d '{"subscriptionId":"s2","customerId":"c2","planId":"BASIC","anchorDay":1}'

# 9/15 主动暂停：立即按剩余比例退 -30*16/30=-16.00，订阅变 PAUSED
curl -s -XPOST localhost:8080/api/subscriptions/s2/pause -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"pause-1","effectiveAt":"2026-09-15T00:00:00Z"}'

# 暂停账段内上报 -> 409 USAGE_DURING_PAUSE（被明确拒绝，不当零、不串周期）
curl -s -XPOST localhost:8080/api/usage -H 'Content-Type: application/json' \
  -d '{"eventId":"x1","subscriptionId":"s2","metric":"api_calls","quantity":9,"usageAt":"2026-09-16T00:00:00Z"}'

# 暂停期间补报暂停前(9/10)真实用量 -> 正常累计到 2026-09 活跃账段
curl -s -XPOST localhost:8080/api/usage -H 'Content-Type: application/json' \
  -d '{"eventId":"x2","subscriptionId":"s2","metric":"api_calls","quantity":120,"usageAt":"2026-09-10T00:00:00Z"}'

# 9/20 恢复：补收 +30*11/30=11.00，订阅回 ACTIVE，本周期配额按活跃 20/30 折算
curl -s -XPOST localhost:8080/api/subscriptions/s2/resume -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"resume-1","effectiveAt":"2026-09-20T00:00:00Z"}'

# 同键重放：返回首次结果，不重复退款/扣款
curl -s -XPOST localhost:8080/api/subscriptions/s2/pause -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"pause-1","effectiveAt":"2026-09-15T00:00:00Z"}'

# 跨周期仍暂停时出账：0 元滚动、保持 PAUSED、不报 ANOMALY
curl -s -XPOST localhost:8080/api/billing/subscriptions/s2/close-period
curl -s localhost:8080/api/admin/reconcile
```

### 5.3 主要接口一览
| 方法 & 路径 | 说明 |
|---|---|
| `POST /api/subscriptions` | 开通（首期预付） |
| `POST /api/subscriptions/{id}/change` `/cancel` | 升降配 / 退订（带 `idempotencyKey`） |
| **`POST /api/subscriptions/{id}/pause` `/resume`** | **客户主动暂停（比例退款）/ 恢复（比例补收，配额折算），带 `idempotencyKey`** |
| `GET /api/subscriptions/{id}` | 查询订阅与当前周期（含 `status`、`pausedSince`） |
| `POST /api/usage` | 用量上报（带 `eventId`；暂停账段内 `USAGE_DURING_PAUSE`） |
| `POST /api/billing/subscriptions/{id}/close-period` | 周期出账 |
| `POST /api/billing/subscriptions/{id}/tick` | 收款状态机推进（重试/宽限/收款失败停用；客户暂停期不动） |
| `POST /api/billing/invoices/{id}/confirm-zero` | 缺计量人工确认零用量 |
| `POST /api/billing/invoices/{id}/late-usage/{eventId}` | 迟到用量补计（配额按活跃时长折算） |
| `POST /api/payments/invoices/{id}/repay` `/refund` | 补缴 / 退款（退款受“不超过已收款”护栏约束） |
| `POST /api/admin/clock/advance` | 拨时钟（`to` 或 `seconds`） |
| `POST /api/admin/gateway/fail-next` | 注入接下来 N 次扣款失败 |
| `GET /api/admin/reconcile` `/ledger` `/plans` | 对账 / 总账 / 套餐 |

## 6. 错误分类（HTTP 状态 + code，原因可区分）
`VALIDATION(400)`、`PLAN_NOT_FOUND/SUBSCRIPTION_NOT_FOUND/INVOICE_NOT_FOUND/NOT_FOUND(404)`、
`DUPLICATE_REQUEST/CHANGE_OUT_OF_ORDER/SUBSCRIPTION_INACTIVE/SUBSCRIPTION_SUSPENDED/
SUBSCRIPTION_PAUSED_BY_CUSTOMER/USAGE_DURING_PAUSE/REFUND_LIMIT_EXCEEDED/
INVOICE_ALREADY_PAID/NOTHING_TO_CLOSE/CONFLICT(409)`、
`GATEWAY_REJECTED(402)`、`INVOICE_ANOMALY/INVALID_AMOUNT/INVOICE_NOT_PAYABLE(400)`。
其中 `SUBSCRIPTION_PAUSED_BY_CUSTOMER`（客户主动暂停）与 `SUBSCRIPTION_SUSPENDED`（收款失败停用）刻意分立。

## 7. 兼容范围与代码结构
- **向后兼容**：未使用暂停/恢复时，订阅状态、账单、对账、既有 15 个用例行为完全不变；
  新增 `PAUSED` 状态、`PAUSE_PRORATION` 明细类型、暂停/恢复接口与独立账单，不改动原有计费结果。
- **边界约定**：暂停/恢复 `effectiveAt` 必须落在当前周期 `[start,end)` 内，且严格晚于上一次
  升降配/暂停/恢复；暂停不支持“预约未来某周期”（请在需要时当周期发起）；暂停态升降配需先恢复。
```
model/        领域模型（套餐/订阅/变更/PauseRecord/用量/账单/支付流水/复式记账事务及枚举）
error/        ErrorCode 分类与 BillingException
repository/   仓储接口（含 PauseRepository）+ ConcurrentHashMap 内存实现
service/      时钟、日历、金额规则、按订阅锁、PauseTimeline 暂停账段计算、
              订阅/暂停/用量/出账/支付/总账/对账 服务及本地模拟通道
service/impl/ 上述服务实现
web/          REST 控制器（含 /pause /resume）与 GlobalExceptionHandler
config/       启动预置套餐
src/test/...  25 个单测/集成测试（含暂停/恢复 10 个）
```
