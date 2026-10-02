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
| `PRORATION` | 中途升降配/退订/暂停/恢复的按比例差价 | 变更立即出一张差价账单 |
| `OVERAGE` | 本周期超配额费 `max(0, 用量-配额)×单价` | 周期出账 |
| `ADJUSTMENT` | 关账后迟到用量补计 / 缺计量人工确认 | 迟到事件追加、人工确认零用量 |

- 周期账单 `金额 = OVERAGE(上一周期) + BASE(下一周期)`；中途差价另立 `PRORATION` 账单，升级补收、降级/退订退回，**绝不与周期账单重复计基础费**。
- 中途变更差价：`(新基础价 - 旧基础价) × 剩余比例`；退订：`-旧基础价 × 剩余比例`；
  暂停：`-旧基础价 × 剩余比例`（退回未消耗部分）；恢复：`+旧基础价 × 剩余比例`（补收剩余部分）。
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
ACTIVE ──客户主动暂停(pause)──▶ PAUSED ──客户主动恢复(resume)──▶ ACTIVE
ACTIVE/PAST_DUE ──退订──▶ CANCELLED（终态）
```
- `SUSPENDED` 是**收款失败**导致的停用：期间变更套餐被 `SUBSCRIPTION_SUSPENDED` 明确拒绝；补缴后自动恢复并滚动周期。
- `PAUSED` 是**客户主动暂停**，与 `SUSPENDED` 是两种可区分的状态：期间不产生基础费、
  暂停时段的用量上报被 `SUBSCRIPTION_PAUSED` 拒绝、变更/退订被 `SUBSCRIPTION_PAUSED` 拒绝
  （需先 resume 再操作）；只能由 `resume` 恢复，不会因补缴而误恢复。
- `CANCELLED` 不再接受用量上报与变更。

### 2.2 账单 InvoiceStatus
```
ANOMALY ──补报 / 人工确认零用量(留 operator)──▶ PENDING
PENDING ──扣款失败──▶ FAILED ──(>3 天)──▶ PAST_DUE(宽限 7 天) ──(超过 dueAt)──▶ 订阅 SUSPENDED
PENDING/FAILED/PAST_DUE ──扣款成功──▶ PAID
负额差价账单 ──通道退款成功──▶ REFUNDED
```
- **重试窗口**：首次失败（`failedAt` 固定，重试不刷新）起 3 天内，`tick` 自动重试；
- **宽限**：超过 3 天进 `PAST_DUE`，`dueAt = 当时 + 7 天`，期内 tick 仍尝试扣款；
- **暂停**：超过宽限仍失败，订阅 `SUSPENDED`；之后通道恢复调用 `repay` 补缴即可；
- 账单的每次通道交互都落 `PaymentAttempt`，`PAID` 账单**恰好一笔成功收款**，不重复、不漏扣。

### 2.3 客户主动暂停 / 恢复（计费口径）
- **暂停 `POST /api/subscriptions/{id}/pause`**：自 `effectiveAt` 生效。
  - 从生效时刻起不再产生基础费；本周期已预付但未消耗的基础费按
    `剩余比例 = (periodEnd - effectiveAt) / (periodEnd - periodStart)` 退回，
    立即出一张负额 `PRORATION` 账单并走通道退款（与退订退款同一口径）；
  - 暂停窗口内的用量上报被 `SUBSCRIPTION_PAUSED` **明确拒绝**：不按零处理、不记到别的周期；
    但 `usageAt` 落在暂停生效点**之前**的迟到补报仍正常累计（按当时的活跃账段计费）；
  - 整周期都处于暂停时，周期结算**不判缺计量异常**（无上报是预期行为），零元账单直接 PAID 并滚动周期；
  - 暂停期间出账**不预收**下一周期基础费；跨周期仍暂停则持续如此，恢复时再按剩余比例补收。
- **恢复 `POST /api/subscriptions/{id}/resume`**：自 `effectiveAt` 生效。
  - 按 `剩余比例` 补收本周期基础费，立即出一张正额 `PRORATION` 账单并扣款；
  - 本周期配额按**活跃时长比例**折算：`有效配额 = includedUnits × 活跃秒数/周期秒数`（HALF_UP 取整），
    暂停掉的时间不占配额；已累计用量在恢复后继续累加，不丢不重；
  - 恢复只能作用于 `PAUSED`；对 `SUSPENDED`（收款失败停用）调用 resume 返回
    `SUBSCRIPTION_SUSPENDED`，引导走补缴流程，两者绝不混淆。
- **暂停窗口推导**（`service/PauseWindows.java`）：由 `PAUSE/RESUME` 变更序列（seq 全序）纯函数推导，
  用量上报、周期出账、对账三处共用同一口径，结论确定可手算。

### 2.4 与既有能力的关系与兼容范围
- **与升降配/退订**：暂停/恢复复用同一 `PlanChange` 流水与 seq 全序、同一订阅锁、同一幂等键体系；
  `PAUSED` 中变更/退订被 `SUBSCRIPTION_PAUSED` 拒绝，须先恢复；乱序（`effectiveAt` 早于上次变更）同样
  `CHANGE_OUT_OF_ORDER` 拒绝。
- **与出账**：出账时配额按活跃比例折算（无暂停的周期比例为 1，行为与此前完全一致，向后兼容）；
  暂停中不预收基础费；整周期暂停不判计量异常。
- **与收款失败停用（SUSPENDED）**：两者是不同枚举值、不同错误码、不同恢复路径
  （resume vs 补缴），互不影响；`PAUSED` 订阅的周期账单若扣款失败仍走 FAILED→宽限流程。
- **与对账**：对账按同一活跃比例重算折算配额核对超配额费；新增退款守恒检查
  （累计退款 ≤ 累计收款），退款执行侧同口径拦截超额退款；三方守恒不变量不变。
- **兼容范围**：未使用暂停的订阅，所有口径（配额、基础费、缺计量判定、对账）与此前完全一致；
  既有 15 个测试用例全部原样通过。

## 3. 确定性、幂等与并发规则

### 3.1 变更的确定性
- 每个订阅一把分段锁（`SubscriptionLocks`），同一订阅的变更/上报/出账/收款**串行**，不同订阅互不阻塞；
- 每次变更 `seq` 单调递增形成**全序**：同一时刻到达的多次变更严格按接收顺序计价，只有一次生效，其余被可解释地拒绝，**无中间态残留**；
- `effectiveAt` 早于上一次变更的请求判 `CHANGE_OUT_OF_ORDER` 拒绝（乱序不覆盖）；
- 变更先出账单草稿再调通道：**通道失败则状态不推进**（仅留可重试的账单状态），用同一 `idempotencyKey` 重试安全。

### 3.2 幂等键
| 操作 | 幂等标识 | 重复请求行为 |
|---|---|---|
| 变更/退订 | `idempotencyKey` | 返回原 `PlanChange`，不二次计价/退款 |
| 暂停/恢复 | `idempotencyKey` | 返回原 `PlanChange`，不重复退款/扣款 |
| 用量上报 | `eventId` | 返回既有累计，不加量；内容冲突则 `DUPLICATE_REQUEST` |
| 收款/补缴/退款/记账 | `idempotencyKey` | 返回原 `PaymentAttempt`/原事务，通道“先成功后超时”也不重复扣款 |

### 3.3 用量累计
- 全局 `eventId` 去重（先判重落事件）+ 订阅锁内累计，`16` 线程并发下同一周期结果唯一确定；
- 累计行有单调 `version`；事件集合 `eventIds` 去重，**重复不加、乱序不丢**；
- **迟到不丢量**：`usageAt` 落在已关账周期照样累计并标记 `lateEventIds`；
  - 原账单未付 → 调整行并入账单重算金额；
  - 原账单已付 → 原账单金额不动，**另立 `ADJUSTMENT` 账单**即时补收；
- **缺计量不按零/上次值处理**：周期内没有任何 `api_calls` 上报时，周期账单置 `ANOMALY`
  （金额 0、不收款、不滚周期），必须补报或由人工 `confirm-zero`（留 `operator` 痕迹）后才继续；
  显式上报 `quantity=0` 属于“已计量”，不是缺失。

## 4. 对账规则（ReconciliationService）
`GET /api/admin/reconcile` 逐张账单与全局检查：
1. 账单金额 = 明细行之和；
2. 周期账单：`OVERAGE + 同周期 ADJ 调整 = max(0, 周期累计用量 - 配额) × 单价`（账单与用量汇总对得上）；
3. 缺计量周期若无“人工确认零用量”留痕却已计价 → 报问题；
4. `PAID` 账单恰好 1 笔成功收款（零元账单无需通道收款，豁免）；`REFUNDED` 负额账单恰好 1 笔成功退款；
5. 每笔记账事务借贷平衡；三方账户余额之和为 0；
6. **退款守恒**：同一订阅累计成功退款 ≤ 累计成功收款（重复/超额退款在此现形；
   退款执行侧同样按此口径拦截，见 2.4）。
返回空 `issues` 即账实相符。

## 5. 本地验证方法

### 5.1 运行测试（含日志打印“输入 + 判定依据”）
```bash
mvn test
```
覆盖（共 22 个用例）：
- `HappyFlowIntegrationTest`：开通→上报→出账→收款→滚周期→对账守恒；
- `ConcurrentUsageReportTest`：**并发重复上报**（16 线程、同 eventId 并发）、迟到/乱序不丢不重；
- `MidCyclePlanChangeTest`：**周期中途升降配**（按秒比例可手算）、同时刻并发变更全序、乱序拒绝、同键重放、退订退款；
- `PaymentFailureRetryTest`：**收款失败→重试→宽限→暂停→补缴恢复**，全程仅 1 笔成功扣款、三方守恒；
- `MissingMeteringTest`：缺计量 ANOMALY、异常账单禁收、人工确认留痕、显式 0 不算缺失；
- `LateUsageAdjustmentTest`：已付账单迟到用量另立调整账单、重复追加拒绝、对账平；
- `PauseResumeTest`（7 个用例）：**客户主动暂停/恢复**——中途暂停比例退款、恢复比例补收与
  配额按活跃时长折算、暂停窗口拒绝用量上报（`SUBSCRIPTION_PAUSED`）、暂停期间补报暂停前用量、
  暂停跨周期不产生基础费且不判缺计量、并发暂停/恢复串行全序、重复提交幂等不重复退款/扣款；
- `ErrorClassificationTest` / `BillingCalendarTest`：错误码可区分、跨月末/闰年周期边界。
日志在 INFO 级别打印每次判定的输入与依据（如用量/配额/比例/费率/通道流水）。

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

# 2.5) 客户主动暂停 / 恢复（带幂等键；差价立即按比例结算）
curl -s -XPOST localhost:8080/api/subscriptions/s1/pause -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"p1","effectiveAt":"2026-09-16T00:00:00Z"}'
curl -s -XPOST localhost:8080/api/subscriptions/s1/resume -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"r1","effectiveAt":"2026-09-21T00:00:00Z"}'

# 3) 用量上报（eventId 幂等；重复提交结果一致；暂停窗口内的 usageAt 会被 409 SUBSCRIPTION_PAUSED 拒绝）
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

### 5.3 主要接口一览
| 方法 & 路径 | 说明 |
|---|---|
| `POST /api/subscriptions` | 开通（首期预付） |
| `POST /api/subscriptions/{id}/change` `/cancel` | 升降配 / 退订（带 `idempotencyKey`） |
| `POST /api/subscriptions/{id}/pause` `/resume` | 客户主动暂停 / 恢复（带 `idempotencyKey`） |
| `GET /api/subscriptions/{id}` | 查询订阅与当前周期 |
| `POST /api/usage` | 用量上报（带 `eventId`） |
| `POST /api/billing/subscriptions/{id}/close-period` | 周期出账 |
| `POST /api/billing/subscriptions/{id}/tick` | 收款状态机推进（重试/宽限/暂停） |
| `POST /api/billing/invoices/{id}/confirm-zero` | 缺计量人工确认零用量 |
| `POST /api/billing/invoices/{id}/late-usage/{eventId}` | 迟到用量补计 |
| `POST /api/payments/invoices/{id}/repay` `/refund` | 补缴 / 退款 |
| `POST /api/admin/clock/advance` | 拨时钟（`to` 或 `seconds`） |
| `POST /api/admin/gateway/fail-next` | 注入接下来 N 次扣款失败 |
| `GET /api/admin/reconcile` `/ledger` `/plans` | 对账 / 总账 / 套餐 |

## 6. 错误分类（HTTP 状态 + code，原因可区分）
`VALIDATION(400)`、`PLAN_NOT_FOUND/SUBSCRIPTION_NOT_FOUND/INVOICE_NOT_FOUND/NOT_FOUND(404)`、
`DUPLICATE_REQUEST/CHANGE_OUT_OF_ORDER/SUBSCRIPTION_INACTIVE/SUBSCRIPTION_SUSPENDED/
SUBSCRIPTION_PAUSED/INVOICE_ALREADY_PAID/NOTHING_TO_CLOSE/CONFLICT(409)`、
`GATEWAY_REJECTED(402)`、`INVOICE_ANOMALY/INVALID_AMOUNT/INVOICE_NOT_PAYABLE(400)`。

## 7. 代码结构
```
model/        领域模型（套餐/订阅/变更/用量/账单/支付流水/复式记账事务及枚举）
error/        ErrorCode 分类与 BillingException
repository/   仓储接口 + ConcurrentHashMap 内存实现
service/      时钟、日历、金额规则、按订阅锁、
              订阅/用量/出账/支付/总账/对账 服务及本地模拟通道
service/impl/ 上述服务实现
web/          REST 控制器与 GlobalExceptionHandler
config/       启动预置套餐
src/test/...  22 个单测/集成测试
```
