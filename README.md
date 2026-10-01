# 按用量计费的订阅计费服务（本地可跑通）

Spring Boot 3 / Java 21 实现的订阅 + 用量混合计费演示系统。**不依赖任何外部支付服务、真实凭据或
不可访问资源**：支付通道是内置的本地模拟组件，存储全部为内存结构，时区固定 UTC 保证跨月末判定一致。

- 金额全程用 `long`（分），无浮点误差；
- 订阅变更与用量上报都是**只追加事件流**，当前状态/累计用量由事件流投影，天然支持乱序/迟到；
- 同一订阅、同一账单分别有互斥锁，支付引用在通道侧幂等，杜绝并发重复扣款/重复计价；
- 每张账单、每次资金动作都可对账，全局复式分类账强制资金守恒。

---

## 1. 计费模型

### 1.1 套餐 Plan

- 月基础费 `basePrice`（按完整月度账期）；
- 多个计量指标 `MetricDef`：每周期包含配额 `includedUnits` + 超额单价 `overagePrice`（分/单位）。

### 1.2 账期 Periods（月度，日锚点）

- 账期锚点 = 开通生效时刻的 UTC 日期；第 n 个账期从锚日所在月 +n 个月的对应日 00:00 UTC 开始；
- 锚日大于短月天数时（如 1 月 31 日开通），该账期边界落在**当月最后一天**，下个月仍回到锚日
  （2/28、3/31…），跨月末结论确定且可解释；
- 账期半开区间 `[periodStart, periodEndExclusive)`。

### 1.3 中途升降配：账段（Segment）+ 按比例计价

订阅事件流按 `(effectiveAt, seq)` 排序后，把账期切成若干 **ACTIVE 账段**，每段内套餐恒定：

- 基础费：`账段基础费 = 月费 × 账段秒数 / 账期秒数`，四舍五入到分；
  退订/暂停时段不产生账段，因此退订后不收费；
- 用量配额随套餐账段走：`段配额 = floor(套餐月配额 × 段秒数 / 账期秒数)`；
- 每条用量上报按「窗口 × 账段重叠秒数比例」分摊到相交账段（取整余差给最后一段），
  **在每个账段内独立**按该段套餐的配额和超额单价计费，再按指标汇总。

  这样升降配后配额/单价随套餐切换确定可解释：升级后高配额低单价只作用于升级后的时间；
  降级同理。同一时刻多次升降配只形成一个边界（同一时刻事件按接收 `seq` 折叠到最终套餐），
  不会出现中间套餐的费用残留。

### 1.4 用量上报 Usage

- 一次上报描述计量窗口 `[windowStart, windowEnd)` 内的**增量**用量；
- 累计与到达顺序无关：按窗口排序后求和，乱序/迟到（窗口落在已出账账期）也不丢量；
- `meteringComplete=false` 表示计量缺失：该指标被标记 `missingMetering`，
  **出账直接阻塞**（错误码 `USAGE_METERING_MISSING`），绝不按 0 或上一次值顶替；
- 超出段配额在指标汇总中标记 `overQuota=true`；
- 窗口落在暂停/退订时段的量计入累计但不计费（日志明示），不静默吞量。

### 1.5 出账与迟到数据

- 一个账期出**一张周期账单**（同订阅+账期唯一，重复出账报 `BILL_ALREADY_EXISTS`）；
- 账期关闭后迟到的变更/上报**不改原票**：用当前全量数据重算期望金额，
  减去该账期已出全部有效票，差额生成一张只含一行 `ADJUSTMENT` 的独立调账票（可负），
  按 `adjustmentKey` 幂等；差额为 0 不出票。原票 + 调账票始终可重放、可解释。

---

## 2. 订阅状态流转

事件流状态机（投影折叠规则）：

```
(未开通) --ACTIVATED(plan)------------------------------> ACTIVE(plan)
ACTIVE   --PLAN_CHANGED(p)------------------------------> ACTIVE(p)
ACTIVE   --SUSPENDED（宽限期到期欠费）-------------------> SUSPENDED
SUSPENDED--RESUMED（补缴且无其他欠票）--------------------> ACTIVE（沿用暂停前套餐）
任意已开通--CANCELLED------------------------------------> CANCELLED（终态）
```

- 事件只追加，允许 `effectiveAt` 早于当前（迟到/乱序），投影按业务时间重排；
- 同一 `effectiveAt` 的多条事件按接收顺序 `seq` 折叠，最终状态确定；
- 暂停态不能升降配（错误码 `SUBSCRIPTION_SUSPENDED`），退订为终态不能再变更
  （`SUBSCRIPTION_ALREADY_CANCELLED`）；恢复事件与暂停事件同一时刻落库，事件流无时间空隙。

### 账单/催收状态机

```
ISSUED ──收款失败──> RETRYING ─再失败─> RETRYING（attempt 累计，可反复重试）
ISSUED/RETRYING ──过 dueAt(出账+7天) 未付──> OVERDUE（同时暂停订阅）
任意未付 ──收款成功──> PAID
OVERDUE ──补缴成功且无其他欠票──> PAID（订阅恢复 ACTIVE）
已付票 ──退款──> 生成独立负向 REFUND 票（原票保留，支持多次部分退款，累计 ≤ 已付）
```

支付通道（`LocalMockPaymentGateway`）：

- 默认成功；`scriptFailFirst(prefix, n, reason)` 模拟前 n 次临时失败（验证重试），
  `scriptAlwaysFail(prefix, reason)` 模拟永久失败（验证宽限/暂停）；
- 收款引用固定为 `charge:<billId>`：**失败不占用幂等键**（同引用可重试），成功后通道缓存结果，
  并发/重复收款由「账单锁 + PAID 状态 + 通道重放」三重拦截，一笔钱最多扣一次；
- 每笔成功扣款手续费 10 分，**退款不退手续费**。

---

## 3. 幂等规则

| 场景 | 幂等手段 | 冲突行为 |
|---|---|---|
| 用量上报 | 调用方 `idempotencyKey`（`putIfAbsent` + 订阅锁） | 同键重放返回首次记录并标记 `duplicate=true`；**同键不同内容**报 `IDEMPOTENCY_KEY_CONFLICT`，首次数据保留 |
| 周期出账 | (订阅, 账期) 唯一 | 重复出账报 `BILL_ALREADY_EXISTS`，迟到数据走调账票 |
| 迟到调账 | `adjustmentKey` | 同键返回已出票；无差额返回空 |
| 收款 | 账单锁 + 固定支付引用 `charge:<billId>` + 通道成功结果缓存 | 已付票再收报 `BILL_ALREADY_PAID`；失败可重试且只成功一次 |
| 退款 | 每次退款独立引用 `refund:<billId>:<uuid>` | 累计退款 ≤ 原票已付，否则 `INVALID_ARGUMENT`；未付票退款 `BILL_NOT_PAYABLE` |
| 订阅开通 | 订阅 ID 唯一（创建映射 + 订阅锁） | 重复开通 `INVALID_ARGUMENT` |

并发：同一订阅的变更/上报/出账串行化；同一账单的收款/催收/退款串行化；
不同订阅/账单互不阻塞。

---

## 4. 对账与资金守恒

`ReconciliationService` 做四层核对，任何一层不符都抛出**可区分错误码**
（`RECONCILE_AMOUNT_MISMATCH` / `LEDGER_NOT_BALANCED`），不静默通过：

1. **账单自洽**：账单金额 = 明细行之和；
2. **账单 ↔ 用量**：发票组（周期票 + 调账票）合计 = 用当前全量事件/上报重算的账期应收；
   OVERAGE 行金额与按指标汇总的超额费对得上；缺量时重算抛 `USAGE_METERING_MISSING`；
3. **账单 ↔ 支付 ↔ 分类账**：每张 PAID 票恰有 1 笔成功扣款尝试、REFUND 票恰有 1 笔成功退款尝试，
   未付票不得存在成功扣款，票款与分录金额一一对应；
4. **全局资金守恒**（复式分类账，`CASH_CLEARING` 为外部往来对方科目）：

```
收款 A、手续费 F：
  借 CASH_CLEARING      A | 贷 SUBSCRIBER_PAID   A
  借 SUBSCRIBER_PAID    A | 贷 PLATFORM_REVENUE A-F
                          | 贷 GATEWAY_FEES       F
退款 R（手续费不退）：
  借 PLATFORM_REVENUE   R | 贷 SUBSCRIBER_PAID    R
  借 SUBSCRIBER_PAID    R | 贷 CASH_CLEARING      R
```

恒有：`Σ借 = Σ贷`，且按「贷-借」计余额 `-CASH_CLEARING = PLATFORM_REVENUE + GATEWAY_FEES`
（`SUBSCRIBER_PAID` 为过渡科目，净额恒 0）。补缴、部分退款后等式依旧成立。

### 错误码分类（HTTP 状态码）

- 404：`PLAN_NOT_FOUND` / `SUBSCRIPTION_NOT_FOUND` / `BILL_NOT_FOUND`
- 400：`INVALID_ARGUMENT` / `USAGE_QTY_INVALID`
- 409：`SUBSCRIPTION_ALREADY_CANCELLED` / `SUBSCRIPTION_SUSPENDED` /
  `BILL_ALREADY_PAID` / `BILL_NOT_PAYABLE` / `BILL_ALREADY_EXISTS` /
  `IDEMPOTENCY_KEY_CONFLICT`
- 422：`USAGE_METERING_MISSING`（计量缺失，禁止出账）
- 500：`LEDGER_NOT_BALANCED` / `RECONCILE_AMOUNT_MISMATCH`
- 502：`GATEWAY_ERROR`

错误响应体形如 `{"code":"...","message":"判定依据...","at":"..."}`，原因可区分。

---

## 5. 本地验证方法

环境：JDK 21、Maven 3.9（无需数据库、无需外网）。

### 5.1 运行全部测试

```bash
mvn test
```

21 个测试覆盖（日志中以 `[case]` 打印输入与判定依据）：

| 测试类 | 覆盖内容 |
|---|---|
| `MoneyProrationTest` | 整数金额、按秒比例计价、四舍五入 |
| `PeriodsTest` | 31 日锚点跨短月、跨月末账期定位 |
| `TimelineTest` | **同刻多次变更单边界**、乱序事件投影、暂停/恢复/退订账段 |
| `ConcurrentUsageReportingTest` | **16 线程并发同幂等键只入账一次**、乱序窗口累计一致、重放标记 |
| `MidPeriodPlanChangeTest` | **周期中途升降配**按比例基础费、段缩放配额、同刻折叠、退订停费 |
| `PaymentDunningFlowTest` | **失败重试成功**、**12 并发收款只扣一次**、永久失败→宽限→暂停→补缴恢复 |
| `RefundAdjustmentEdgeCaseTest` | 部分退款守恒/手续费不退、缺量阻塞+标记、迟到上报调账及幂等、错误码区分 |
| `HttpEndToEndTest` | REST 全链路（开通→上报→重复上报→出账→收款→对账→守恒→404 分类） |

### 5.2 启动服务手工验证

```bash
mvn spring-boot:run
# server.port=0 表示随机端口，启动日志中查找 Tomcat started on port(s): xxxx
# 如需固定端口，在命令行加 -Dspring-boot.run.arguments=--server.port=8080
```

curl 走查（端口设为 8080）：

```bash
# 1. 建套餐：月费 2000 分，api 配额 100，超额 3 分/次
curl -s -XPUT localhost:8080/api/plans/p1 -H 'Content-Type: application/json' -d '{
  "id":"p1","name":"Pro","basePriceCents":2000,
  "metrics":[{"key":"api","includedUnits":100,"overagePriceCents":3,"unit":"call"}]}'

# 2. 开通订阅（指定生效时间，便于确定性验证）
curl -s -XPOST localhost:8080/api/subscriptions -H 'Content-Type: application/json' -d '{
  "subscriptionId":"s1","customerId":"c1","planId":"p1",
  "effectiveAt":"2026-09-01T00:00:00Z"}'

# 3. 周期中途升配（先建第二个套餐）
curl -s -XPUT localhost:8080/api/plans/p2 -H 'Content-Type: application/json' -d '{
  "id":"p2","name":"Max","basePriceCents":4000,"metrics":[]}'
curl -s -XPOST localhost:8080/api/subscriptions/s1/change-plan -H 'Content-Type: application/json' -d '{
  "newPlanId":"p2","effectiveAt":"2026-09-16T00:00:00Z"}'

# 4. 上报用量（重复执行第二次返回 duplicate:true，不重复计费）
curl -s -XPOST localhost:8080/api/subscriptions/s1/usage -H 'Content-Type: application/json' -d '{
  "idempotencyKey":"u1","metricKey":"api","quantity":150,
  "windowStart":"2026-09-01T00:00:00Z","windowEnd":"2026-09-10T00:00:00Z",
  "meteringComplete":true}'
# 计量缺失的上报（会阻塞该账期出账，返回 422 USAGE_METERING_MISSING）
curl -s -XPOST localhost:8080/api/subscriptions/s1/usage -H 'Content-Type: application/json' -d '{
  "idempotencyKey":"u2","metricKey":"api","quantity":0,
  "windowStart":"2026-09-10T00:00:00Z","windowEnd":"2026-09-11T00:00:00Z",
  "meteringComplete":false}'

# 5. 出账（基础费按两个账段比例；存在缺量时返回 422）
curl -s -XPOST localhost:8080/api/subscriptions/s1/bills -H 'Content-Type: application/json' -d '{"periodIndex":0}'

# 6. 收款（模拟失败：支付行为由 LocalMockPaymentGateway 的脚本控制，见集成测试用法）
curl -s -XPOST localhost:8080/api/subscriptions/s1/bills/<billId>/collect
# 宽限检查（at 超过 dueAt 会转 OVERDUE 并暂停订阅）
curl -s -XPOST localhost:8080/api/subscriptions/s1/bills/<billId>/evaluate-dunning \
  -H 'Content-Type: application/json' -d '{"at":"2026-10-20T00:00:00Z"}'
# 补缴
curl -s -XPOST localhost:8080/api/subscriptions/s1/bills/<billId>/pay-overdue

# 7. 关闭账期后迟到数据 -> 调账票
curl -s -XPOST 'localhost:8080/api/subscriptions/s1/bills/adjustments?periodIndex=0' \
  -H 'Content-Type: application/json' -d '{"adjustmentKey":"adj-001"}'

# 8. 退款（部分退款）
curl -s -XPOST localhost:8080/api/subscriptions/s1/bills/<billId>/refund \
  -H 'Content-Type: application/json' -d '{"amountCents":500,"reason":"goodwill"}'

# 9. 单票对账 + 全局守恒
curl -s -XPOST localhost:8080/api/subscriptions/s1/bills/<billId>/verify
curl -s -XPOST localhost:8080/api/admin/verify-conservation
curl -s localhost:8080/api/admin/ledger/accounts
```

### 5.3 主要代码索引

- 金额/账期/投影：`common/Money`、`engine/Periods`、`engine/Proration`、`engine/Timeline`
- 计价核心：`service/BillingService`（账段基础费、段配额分摊、缺量阻塞、调账差额）
- 收款/守恒：`service/PaymentService`、`gateway/LocalMockPaymentGateway`、`service/ReconciliationService`
- 并发控制：`repo/SubscriptionRepository.lockOf`、`repo/BillRepository.lockOf`
