# WMS 仓储管理系统（微服务版）

> 本文档为微服务拆分后的最终交付说明。端到端集成验证过程、逐条结论与缺陷清单见
> [`docs/VERIFICATION-REPORT.md`](docs/VERIFICATION-REPORT.md)。

## 1. 项目简介与架构

本工程由原单体 WMS 拆分而来，采用 **服务注册发现 + OpenFeign 服务间调用 + database-per-service** 的微服务架构。

- **运行基线**：JDK 17、Spring Boot 3.2.5、Spring Cloud 2023.0.2、Maven 3.9.9。
- **注册中心**：`wms-eureka-server`（Eureka），各服务启动后自动注册并通过负载均衡调用。
- **服务间调用**：全部经由 `wms-common` 中 `com.jim.wms.common.api` 包下的 4 个 Feign 接口，服务内不得自建 `@FeignClient` 直连其他服务路径。
- **数据隔离**：每个业务服务独占一个 MySQL 库，跨服务读不走对方库表，只能走 Feign 契约。
- **库存唯一写者**：实物库存台账与流水的唯一写者是 `wms-inventory-service`；入库、出库、盘点、退货、报表等服务一律通过 `InventoryApi` 访问库存。
- **最终一致性**：出库确认采用「本地置 CONFIRMING + 补偿任务表 + Feign 扣减 + 失败置 CONFIRM_FAILED + 定时任务重试 + 全成功才 FINISHED」；库存侧按 `biz_key` 幂等。
- **锁与中间件**：库存并发互斥由 Redis 分布式锁承担，Redis 是正常运行路径的必需依赖；Redis 不可用时默认快速失败（body.code=503），不静默降级、不静默放行。Kafka 事件改走事务性发件箱：业务事务内只落一条待发记录，真正的 Kafka 投递由独立 Relay 在事务外、锁外完成；临界区内不再同步等待生产者元数据，Kafka 短时不可用不会拉长持锁时间。生产环境必须部署 Redis；Kafka 用于异步事件投递，短时不可用期间事件积压在发件箱，恢复后由 Relay 回补。

## 2. 端口表

| 模块 / 服务 | 端口 | 职责 | 数据库 |
| --- | --- | --- | --- |
| wms-eureka-server | 8761 | Eureka 服务注册中心 | 无 |
| wms-admin-service | 8081 | 主数据：仓库、库区、库位、SKU 维护与查询 | jim_wms_admin |
| wms-inventory-service | 8082 | 库存台账、库存流水、冻结/解冻；库存唯一写者 | jim_wms_inventory |
| wms-inbound-service | 8083 | 入库收货、质检、上架 | jim_wms_inbound |
| wms-outbound-service | 8084 | 出库拣货、确认、补偿任务与重试 | jim_wms_outbound |
| wms-transfer-service | 8085 | 跨 DC 调拨出库 / 入库单 | jim_wms_transfer |
| wms-stocktake-service | 8086 | 盘点单、盘点项、差异处理 | jim_wms_stocktake |
| wms-return-service | 8087 | 退货单、退货上架 | jim_wms_return |
| wms-report-service | 8088 | 报表聚合（通过 Feign 取库存，自身不直连库存表） | jim_wms_report（无业务表） |
| wms-integration-service | 8089 | CTM / OMS 对接：补货调拨与销售出库推送、幂等登记、取消、状态回调 | jim_wms_integration |

## 3. 库名表与表归属

| 数据库 | 业务表 | 归属说明 |
| --- | --- | --- |
| jim_wms_admin | wms_warehouse、wms_area、wms_location、wms_sku | 主数据，admin 唯一写者 |
| jim_wms_inventory | wms_inventory、wms_inventory_log | **库存台账与流水唯一写者为 inventory 服务** |
| jim_wms_inbound | wms_inbound_order、wms_inbound_item | 入库单与入库明细 |
| jim_wms_outbound | wms_outbound_order、wms_outbound_item、wms_outbound_stock_task | 出库单、出库明细；**wms_outbound_stock_task 为出库扣减补偿任务表** |
| jim_wms_transfer | wms_transfer_order | 调拨单 |
| jim_wms_stocktake | wms_stocktake_order、wms_stocktake_item | 盘点单与盘点明细 |
| jim_wms_return | wms_return_order | 退货单 |
| jim_wms_report | （无业务表，仅 flyway_schema_history） | 报表口径全部经 Feign 从 inventory 取数 |
| jim_wms_integration | wms_external_order | 外部单据幂等登记表 |

> 约束：除 inventory 服务外，任何服务源码不得出现 `InventoryMapper`、`com.jim.wms.inventory` 包引用或 `wms_inventory` 表名；各服务 Flyway 迁移脚本只允许建自己库内的表。

## 4. 服务依赖图

```
                         ┌──────────────────────────┐
                         │  wms-eureka-server :8761 │
                         └──────────────┬───────────┘
                                         │ 注册 / 发现
        ┌───────────────┬────────────────┼────────────────┬───────────────┐
        │               │                │                │               │
┌───────▼──────┐  ┌──────▼───────┐  ┌─────▼──────┐  ┌──────▼──────┐ ┌──────▼───────┐
│ integration  │  │   outbound   │  │  inbound   │  │  report     │ │ stocktake    │
│   :8089      │  │    :8084     │  │   :8083    │  │   :8088     │ │ :8086        │
└───────┬──────┘  └──────┬───────┘  └─────┬──────┘  └──────┬──────┘ └──────┬───────┘
        │ OutboundApi     │ InventoryApi   │ InventoryApi│ InventoryApi  │ InventoryApi
        │ replenish-order │ change-stock  │ change-stock│ list          │ list / change-stock
        │ cancel          │ IntegrationApi│             │               │
        ▼                 ▼               └──────┬──────┘               ▼
┌──────────────────┐ ┌─────▼────────────────────────────────────────────┐
│ outbound :8084   │ │              inventory :8082（库存唯一写者）        │
└──────────────────┘ └────────────────────────────────────────────────────┘
        │ IntegrationApi.ctmCallback ▲
        └───────────────────────────┘  outbound 回调 integration 转发 CTM

return :8087 ──InventoryApi.change-stock──▶ inventory :8082
transfer :8085 ── 本期无 Feign 调用（人工确认后落单）
admin :8081 ── 暴露 AdminApi（warehouse/location/sku 查询）；integration 的 OMS 建单流程已注入消费做主数据校验
```

| 调用方 | 被调方 | Feign 契约（wms-common） | 用途 |
| --- | --- | --- | --- |
| integration | outbound | OutboundApi.replenishOrder / cancel | CTM 推送生成出库单、取消出库单 |
| integration | outbound | OutboundApi.createSalesOrder | OMS 销售出库生成出库单 |
| integration | admin | AdminApi.getWarehouse / getLocation / getSku | OMS 建单前仓库 / 库位 / 商品主数据校验 |
| outbound | inventory | InventoryApi.changeStock | 出库扣减库存 |
| outbound | integration | IntegrationApi.ctmCallback | 出库终态回调，由 integration 转发 CTM |
| outbound | integration | IntegrationApi.notifyOmsStatus | 出库终态通知 OMS，由 integration 转发 OMS |
| inbound | inventory | InventoryApi.changeStock | 上架增加库存 |
| report | inventory | InventoryApi.list | 报表库存聚合取数 |
| stocktake | inventory | InventoryApi.list / changeStock | 盘点取数、盘盈盘亏调整 |
| return | inventory | InventoryApi.changeStock | 退货上架回补库存 |
| admin | — | （被读方）AdminApi 已暴露，预留主数据查询 |

## 5. 构建与启动

### 5.1 全量构建

```bash
cd D:\wms\wms-backend
mvn -B -DskipTests clean package
```

构建产物位于各模块 `target/` 下，可执行 jar 为 `wms-<module>-1.0.0.jar`。

### 5.2 前置依赖

- **MySQL 8.0**：必须在线（`127.0.0.1:3306`，本环境 root/root）；9 个业务库与 Flyway 历史表已就绪。
- **Redis（6379）**：**必需**。库存台账/流水的并发互斥由 Redis 分布式锁承担；不可用时默认抛 503 快速失败，不静默降级、不静默放行。
- **Kafka（9092）**：事件经发件箱异步投递，已实测在线生产与消费（见 5A.1 与验证报告 1C 节）。broker 短时不可用时事件积压在 `wms_event_outbox`，Relay 按退避重试回补；生产环境应部署 Kafka 并消费两个 topic。
- **CTM 回调端（默认 `http://127.0.0.1:9000`）**：本验证环境不存在，回调失败仅记录 ERROR 待补偿。

### 5.2.1 分布式锁实现要点与配置

库存唯一写者 `wms-inventory-service` 的 `changeStock` / `freeze` 在临界区前先获取 Redis 锁、`try/finally`（或事务回调）释放。锁实现位于 `wms-common` 的 `com.jim.wms.common.lock` 包：

- **加锁**：`SET key token PX lease NX`；锁值 token = `实例标识:线程ID:UUID`，实例标识为 `spring.application.name:server.port`，不同实例/线程/同线程多次获取均不同。
- **释放**：Lua 脚本比对 token 后再 `del`，防止误删他人锁；返回 0（锁已过期或被他人持有）只记 WARN，不删除。
- **有界等待**：在 `wait-timeout` 内按 `retry-interval` 重试，超时抛 **429**（文案「该库存维度正在被其他任务变更，请稍后重试: <key>」）。
- **Redis 不可用**：连接失败/超时等异常抛 **503** 快速失败（文案「Redis 锁服务不可用，已拒绝本次库存变更: …」），不无限等待、不静默放行。
- **释放时机**：存在活动事务时在 `afterCompletion`（提交/回滚后）释放；无事务时在 `finally` 释放；`AtomicBoolean` 保证只释放一次，释放失败只记 ERROR，不覆盖原始业务异常。
- **启动自检**：`@PostConstruct` PING 一次 Redis，成功记 INFO「Redis 锁通道就绪…PING=PONG」；失败记 ERROR，不阻断启动。

`wms.lock` 配置项（前缀 `wms.lock`，9 个服务 `application.yml` 一致）：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `wms.lock.wait-timeout` | `2000ms` | 获取锁的有界等待时长，超时抛 429 |
| `wms.lock.lease-time` | `30s` | 锁租约时长（Redis key 的 PX 过期时间），须大于最长业务耗时 |
| `wms.lock.retry-interval` | `50ms` | 未抢到锁时的重试间隔 |
| `wms.lock.allow-local-fallback` | `false` | 是否允许 Redis 不可用时回退进程内锁兜底 |

> **`allow-local-fallback` 风险**：默认 `false`，正常运行路径完全不经过本地锁。仅在「确实无 Redis 的单机开发」时可显式置 `true`；**生产与多实例环境禁止开启**——回退为 JVM 内 `ReentrantLock` 后，多实例之间互斥语义失效，并发变更同一库存维度会丢失更新、存在超卖风险。

### 5.3 启动顺序

1. 先起注册中心：`wms-eureka-server`（8761）。
2. 再起底座服务：`wms-admin-service`（8081）、`wms-inventory-service`（8082）。
3. 然后起业务服务：`wms-outbound-service`（8084）、`wms-integration-service`（8089），其余 `wms-inbound-service`、`wms-report-service`、`wms-transfer-service`、`wms-stocktake-service`、`wms-return-service`。

单个服务启动示例：

```bash
java -jar wms-admin-service\target\wms-admin-service-1.0.0.jar
java -jar wms-inventory-service\target\wms-inventory-service-1.0.0.jar
java -jar wms-inbound-service\target\wms-inbound-service-1.0.0.jar
java -jar wms-outbound-service\target\wms-outbound-service-1.0.0.jar
java -jar wms-report-service\target\wms-report-service-1.0.0.jar
java -jar wms-integration-service\target\wms-integration-service-1.0.0.jar
```

## 5A. 2026-11-18 结构性改造说明

### 5A.1 事务性发件箱（outbox）机制

库存台账变更与出库/入库单据状态事件不再直接在业务流程内同步发送 Kafka，改为事务性发件箱模式：

- **写入侧**：业务本地事务内由 `OutboxWriter` 只往本库 `wms_event_outbox` 写一条 `PENDING` 记录（topic、event_key、payload、retry_count、next_retry_time、时间戳）；台账/单据变更与发件箱记录在同一个本地事务提交，事务一起回滚，保证「台账变了事件不会永久丢」。写发件箱失败抛运行时异常，不允许吞掉。
- **投递侧**：`OutboxRelay` 由 `wms-common` 自动装配，在业务事务之外按扫描间隔轮询；先用条件 UPDATE 把记录置 `SENDING` 并写 owner/lease_until，影响 1 行才处理，避免多实例重复投递；发送成功置 `SENT` 并清空 last_error；失败累加 retry_count、记录 last_error、按 backoff 顺延 next_retry_time，达到 `max-retry` 后置 `FAILED` 不再自动重试。投递线程崩溃时，`SENDING` 记录在 `sending-lease-ms` 租约到期后被回收重置为 `PENDING`。
- **临界区关系**：库存锁临界区内不再调用任何 Kafka API，仅多一条同事务数据库写入；真实生产者元数据等待与网络发送都在 Relay 线程完成。两个 topic 名未改：`wms-stock-event`（key 为 `仓库:SKU`，同维度有序）、`wms-order-status`（key 为出库单号）。
- **接入范围**：`wms-inventory-service`、`wms-inbound-service`、`wms-outbound-service` 配置 `wms.outbox.enabled=true`；其余服务未配置，发件箱默认关闭，不会因缺少发件箱表而启动失败。表由各服务自己的 Flyway 迁移创建（inventory/inbound V2、outbound V3）。

发件箱状态机：`PENDING → SENDING → SENT`；`SENDING` 失败 → `FAILED`，到期后可再次被认领；`SENDING` 租约过期 → 回收回 `PENDING`。

### 5A.2 `wms.outbox.*` 配置默认值

逐项核对自 `wms-inventory-service`、`wms-inbound-service`、`wms-outbound-service` 的 `src/main/resources/application.yml`，与 `wms-common\...\outbox\OutboxProperties.java` 的 Java 默认值一致：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `wms.outbox.enabled` | 三个生产方 yml 显式 `true`；未配置的服务取 Java 默认 `false` | 是否装配 Writer/Relay；仅事件生产方打开 |
| `wms.outbox.scan-interval-ms` | `1000` | Relay 扫描间隔（毫秒） |
| `wms.outbox.batch-size` | `50` | 单轮最多处理的记录数 |
| `wms.outbox.max-retry` | `20` | 最大投递重试次数，达到后置 `FAILED` 不再自动重试 |
| `wms.outbox.send-timeout-ms` | `5000` | 单条 Kafka 发送同步等待上限（毫秒） |
| `wms.outbox.backoff-ms` | `5000` | 失败重试退避基准（毫秒），每次失败按该值顺延 next_retry_time |
| `wms.outbox.sending-lease-ms` | `60000` | `SENDING` 记录租约（毫秒），超时未回写视为投递线程崩溃，重置回 `PENDING` |

运维口径：排查事件积压先查各库 `wms_event_outbox` 的 `status`、`retry_count`、`last_error`、`next_retry_time`；`FAILED` 达到上限后需人工介入核对并重投；消费者侧积压、重试与镜像对账本轮未覆盖。

### 5A.3 出库补偿任务三态认领与租约

补偿任务 `wms_outbound_stock_task` 状态扩展为 `PENDING / PROCESSING / SUCCESS / FAILED / CANCELLED`，V2 迁移新增 `owner`（认领者 `实例:端口:线程`）、`lease_until`（租约到期时间）与 `(status, lease_until)` 候选索引：

- **候选查询**：`PENDING/FAILED` 且 retry_count 未超上限且 next_retry_time 到期，或 `PROCESSING` 且租约已过期；顺序按 id 升序，命中既有 `idx_status_retry` 与 `idx_status_lease` 索引。
- **认领**：`claimNew`（扫描器认 PENDING/FAILED）、`claimExpired`（回收过期 PROCESSING）、`claimFirstAttempt`（confirm 首次同步认领，仅认 PENDING 且 retry_count 为 0，不判到期）均为单条条件 UPDATE，影响 1 行才算抢到；认领时 retry_count 自增 1，写 owner 与 lease_until。
- **独立事务**：认领在 `REQUIRES_NEW` 独立事务内提交后才出事务做 Feign 扣减；Feign 调用不被包在大事务里。
- **续租**：`renewLease` 仅当前 owner 匹配才续；Feign 前后各续租一次。
- **终态回写**：SUCCESS / FAILED / CANCELLED 均清空 owner 与 lease_until；SUCCESS 同时清空 last_error、next_retry_time；FAILED 写 last_error、retry_count、next_retry_time。
- **上限截断**：`retry_count` 达到 `max-retry=5` 后，认领 UPDATE 影响 0 行，不再被自动认领。

### 5A.4 `wms.outbound.*` 配置默认值、取值依据与风险

核对自 `wms-outbound-service\src\main\resources\application.yml` 与 `OutboundProperties.java`：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `wms.outbound.lease-seconds` | `60` | 任务认领租约时长（秒） |
| `wms.outbound.scan-interval-ms` | `30000` | 补偿任务扫描间隔（毫秒），须显著小于租约 |
| `wms.outbound.pending-threshold-ms` | `10000` | 插入 PENDING 时 next_retry_time 等于创建时间加该阈值（毫秒） |
| `wms.outbound.doc-reconcile-threshold-ms` | `120000` | 单据停留 CONFIRMING 超过该值触发单据维度补扫（毫秒） |
| `wms.outbound.max-retry` | `5` | 任务最大认领/重试次数，达到后不再自动认领 |
| `wms.outbound.lease-renew-interval-ms` | `20000` | 预留配置项；当前实现仅在 Feign 前后各续租一次，未按该间隔做周期续租 |

取值依据：单次远程最坏耗时取分布式锁等待 2s 加 Feign 读超时 10s，约 12s；租约取其约 5 倍为 60s；扫描间隔 30s 显著小于租约，避免任务在租约内被误回收。风险：若后续单次业务耗时（锁等待、Feign 超时）上调，租约 60s 可能不足，需同步上调 `lease-seconds`；`lease-renew-interval-ms` 目前是预留项，长耗时步骤不会按 20s 周期续租，仅靠 Feign 前后两次续租兜底。

### 5A.5 本轮七项改造与 OMS 入口对照表

| # | 改造项 | 落地位置 | 本轮验证结论 |
| --- | --- | --- | --- |
| 1 | 事件改事务性 outbox | `wms-common\outbox` 内核；inventory/inbound/outbound 三处接入 | Kafka 在线 20 并发 outbox 20 条 SENT、topic 20 条；bootstrap 不可达场景 20 条经回补全部 SENT；事件移出库存锁临界区 |
| 2 | 三态认领与租约 | outbound 任务表 V2；Mapper 认领/回收/续租/终态方法 | 双实例认领影响行 1 与 0 分布；过期 PROCESSING 被回收；未过期租约不被抢；retry_count 认领时自增，达到 5 截断 |
| 3 | PENDING 超时纳入扫描并统一时钟 | `selectCandidates`；next_retry_time 由应用时钟计算 | 候选覆盖 PENDING/FAILED 到期与过期 PROCESSING；PENDING 与 create_time 差值 10000ms，同一 now 来源 |
| 4 | 多库位多批次拣货扣对库位 | item 生成列 `batch_key` 唯一键；`buildRequestId` 含库位批次 | 同单两行明细两条任务两个 requestId；各自库位 5.000 → 3.000；流水按库位各 1 行，无跨库位扣错 |
| 5 | PICKED 后禁止直接取消 | `cancel` 409 判定 | PICKED 且存在 picked_qty>0 明细返回 409 原文；未拣货单据取消 CANCELLED，不回滚已扣库存 |
| 6 | 调拨在途断链打通 | `TransferApi` 契约；transfer `markInTransit`/`receive` | TRANSFER_OUT 全 SUCCESS 后调拨单 CREATED → IN_TRANSIT 并回填 outbound_no；404 只 WARN + 单据级 last_error；重复在途幂等不回退；收货置 FINISHED |
| 7 | 单据维度对账补扫 | `reconcileConfirmingOrders`，120s 阈值 | 缺任务单据补建 PENDING 后推终态；任务全 SUCCESS 但单据卡住的推 FINISHED；补扫幂等，不重复扣减、不重复发事件 |
| 8 | OMS 外部入口 | integration `/integration/oms/outbound/order`、`/cancel` | 首次推送 200；重复推送 409；主数据不存在 400；PICKED 取消 409 原样透传；回调地址默认空，只记 INFO 跳过 |

### 5A.6 确认语义修复说明

- **旧现象**：健康链路 confirm 返回 200 后，单据先落 `CONFIRM_FAILED`、任务仍 `PENDING`，需等一个 30s 扫描周期才被认领扣减推到 FINISHED，健康链路每次确认都先落一个误报失败态。
- **修法**：新增 `claimFirstAttempt` 供 confirm 首次同步认领（仅 PENDING 且 retry_count=0，不判 next_retry_time 到期）；dispatch 返回三态（未认领 / 成功 / 远程失败）；confirm 结束重查全部任务再判定——全 SUCCESS 推终态，存在 FAILED 置 CONFIRM_FAILED，仅在途或被别实例抢走则保持 CONFIRMING；终态幂等守卫在单据已 FINISHED/PICKED 时直接返回，不重复写单据、发件箱事件与回调。
- **实测**：健康链路 confirm 耗时 2.303s 返回后立即查库即 FINISHED，未观察到 CONFIRM_FAILED 中间态；终态 `wms-order-status` 发件箱仅 1 条 SENT。
- **插入规则未改**：`next_retry_time` 仍等于创建时间加 `pending-threshold-ms`（10000ms），未改为 now；既有约束（PICKED 409 文案、retry 上限 5、租约 60s、扫描 30s、补扫 120s、requestId 规则、发件箱事务语义）均照旧。

## 6. 验证结论（本阶段实测）

> 以下数据来自 2026-11-17 在本环境的真实运行；逐条命令、原始 HTTP 响应与 SQL 结果索引见 `docs/VERIFICATION-REPORT.md`。

### 6.1 全量构建 —— 已实测（返工后重建）

- 返工改动后重新执行 `mvn -B -DskipTests clean package`：**12 个 reactor 条目（父 POM + 11 模块）全部 SUCCESS**，BUILD SUCCESS，Maven 报告 Total time **36.314 s**（Finished 2026-11-17T14:04:33+08:00）。第一轮联调前构建同样 12/12 SUCCESS。
- Redis 分布式锁改造回归轮再次重跑全量构建：**12/12 reactor SUCCESS**，BUILD SUCCESS，Maven Total time **37.887 s**（墙钟 39.355 s，Finished 2026-11-17T14:53:38+08:00）。
- 9 个业务服务 + eureka 的可执行 jar 均存在；MANIFEST `Main-Class=JarLauncher`，`Start-Class` 指向各 `*ServiceApplication` / `EurekaServerApplication`，启动类核对一致。

### 6.2 Eureka 注册 —— 已实测

本轮同时在线并在 Eureka 中 UP 的实例（6 个）：

| 服务 | 端口 | 状态 |
| --- | --- | --- |
| wms-admin-service | 8081 | UP |
| wms-inventory-service | 8082 | UP |
| wms-inbound-service | 8083 | UP |
| wms-outbound-service | 8084 | UP |
| wms-report-service | 8088 | UP |
| wms-integration-service | 8089 | UP |

> transfer / stocktake / return 本轮未启动，未在注册清单中。
>
> 返工回归轮实测：eureka(8761) + admin(8081) + inventory(8082) + outbound(8084) + integration(8089) 共 5 进程在线，Eureka 中 4 个业务应用均 UP（证据 `docs/VERIFICATION-REPORT.md` 回归节，`regression/http/eureka-apps.json`）。

### 6.3 入库 → 库存 Feign 成功链路（inbound → inventory）—— 已实测

- 单据 `IN1794892935664`（SKU-001，批次 B-E2E-01，库位 DC01-A-01-01，数量 20）。
- 变更前：SKU-001 DC01 在库 15.000（批次 B001）；库存流水 2 行。
- 变更后：新增台账行 B-E2E-01 **20.000**；新增流水 `biz_key=IN:IN1794892935664:SKU-001:DC01-A-01-01:B-E2E-01`，类型 PURCHASE_IN，**+20.000**（before 0.000 → after 20.000）；入库单状态 **FINISHED**，明细 receivedQty=20、putawayQty=20、QC=PASS。

### 6.4 出库 → 库存 Feign 成功链路（outbound → inventory）—— 已实测

- 单据 `OUT1794893009820`（SKU-001，批次 B-E2E-01，拣货 8）。
- 变更前：SKU-001 DC01 在库 **35.000**（B001 15.000 + B-E2E-01 20.000）。
- 变更后：B-E2E-01 台账 **20.000 → 12.000**；补偿任务 `OUT:OUT1794893009820:SKU-001:DC01-A-01-01:B-E2E-01` 状态 **SUCCESS**，retry_count=0；新增流水 SALE_OUT **-8.000**（before 20.000 → after 12.000）；出库单 **FINISHED**。

### 6.5 补偿闭环（失败 → 补库存 → 定时重试成功）—— 已实测

单据 `OUT1794893070048`（SKU-002，批次 B-E2E-02，计划/拣货 10，初始库存 0）。

| 阶段 | 时间 | 单据状态 | 补偿任务 | 库存 / 流水 |
| --- | --- | --- | --- | --- |
| 阶段一：确认时库存不足 | 13:24:47 | CONFIRM_FAILED | FAILED，retry_count=1，next_retry_time=13:25:47，last_error=`code=400, msg=库存不足, skuCode=SKU-002, 现有=0, 需要=10.0000` | 无 SKU-002 台账，无扣减流水（0 行） |
| 阶段二：入库补 10 件后（重试前） | 13:25:38 | CONFIRM_FAILED | FAILED，等待到期 | 台账 B-E2E-02 **10.000**；流水 IN +10.000 |
| 阶段三：定时任务重试成功 | 13:26:36 | FINISHED | SUCCESS，retry_count=1 | 台账 **0.000**；流水仅 1 条 IN +10.000 与 1 条 OUT -10.000，**未因重试重复扣减** |

### 6.6 CTM 对外契约（integration）—— 已实测

- 首次推送 `CTM-E2E-001`：body.code=200，data=出库单号 `OUT1794893255478`。
- 同 `ctmOrderNo` 二次推送：HTTP 200，**body.code=409**，msg 逐字为 `单据已接收，请勿重复推送: CTM-E2E-001`。
- `wms_external_order`：source=CTM，businessType=REPLENISH，warehouse=DC01，status=**RECEIVED**；映射出库单 CREATED。
- 取消接口空 `wmsOutboundNo`：body.code=400，msg=`wmsOutboundNo 不能为空`。
- 带号取消（`OUT1794893342705` / `CTM-E2E-002`，取消前任务为 FAILED）：body.code=200；出库单 **CANCELLED**，补偿任务 **CANCELLED**；跨过下一轮补偿扫描（13:31:11）复核仍为 CANCELLED，**未被重试复活**，批次 B-E2E-03 无扣减流水（0 行）。另一单 `OUT1794893255478` 取消后同样 CANCELLED。

### 6.7 报表 Feign 聚合（report → inventory）—— 已实测

`/api/report/stock-summary?warehouseCode=DC01`：

| SKU | onHandQty | freezeQty | availableQty | SQL 直算核对 |
| --- | --- | --- | --- | --- |
| SKU-001 | 27.000 | 0.000 | 27.000 | SUM(qty)=27.000，SUM(freeze)=0.000，一致 |
| SKU-002 | 0.000 | 0.000 | 0.000 | 一致 |

### 6.7.1 CTM TRANSFER 调拨链路（返工核心回归）—— 已实测

返工后重跑端到端：CTM 推送 `orderType=TRANSFER`，链路落库与流水类型逐条 SQL 核对（数量精确到小数位）。

| 步骤 | 操作 / SQL | 实测结果 |
| --- | --- | --- |
| 补货 | inventory 内部 `/internal/inventory/change-stock`，SKU-001 / DC01-A-01-01 / B-REG-01，+10.000，PURCHASE_IN | 台账 0.000 → **10.000**；流水 `PURCHASE_IN` +10.000（before 0.000 → after 10.000） |
| 推送调拨单 | POST `/integration/ctm/replenish/order`，`ctmOrderNo=CTM-REG-001`，`orderType=TRANSFER`，DC01→DC02，3 件 | body.code=200，data=出库单号 `OUT1794895803002`；`wms_outbound_order.order_type=**TRANSFER_OUT**`（非 REPLENISH）；`wms_external_order.business_type=TRANSFER`，status=RECEIVED |
| 拣货 | POST `/api/outbound/pick`，SKU-001 / DC01-A-01-01 / 3 | body.code=200 |
| 确认 | POST `/api/outbound/confirm`，`OUT1794895803002` | body.code=200；单据 **FINISHED** |
| 库存核对 | SQL 查 `wms_inventory` | 台账 **10.000 → 7.000**（-3.000，精确到小数位） |
| 流水核对 | SQL 查 `wms_inventory_log` | 新行 `business_type=**TRANSFER_OUT**`（非 SALE_OUT），change **-3.000**，before 10.000 → after 7.000，biz_key=`OUT:OUT1794895803002:SKU-001:DC01-A-01-01:B-REG-01` |
| 补偿任务核对 | SQL 查 `wms_outbound_stock_task` | 状态 **SUCCESS**，retry_count=0，next_retry_time=NULL，**`last_error` 为 NULL**（D3 修复复测通过） |

> 结论：**CTM TRANSFER 单 → WMS TRANSFER_OUT → 库存流水 business_type=TRANSFER_OUT** 全链路已实测通过；成功补偿任务的错误信息字段已正确置空。

### 6.10 OMS 销售出库入口（integration）—— 已实测（2026-11-18）

OMS 经 `wms-integration-service` 的 `/integration/oms/**` 两条入口下发销售出库指令；订单类型由服务端固定为 `SALE_OUT`，**不接受外部传入订单类型**。

**对外路径与请求字段**

| 路径 | 方法 | 请求体 | 返回 |
| --- | --- | --- | --- |
| `/integration/oms/outbound/order` | POST | `OmsOutboundOrderCmd` | `Result<String>`，`data` 为 WMS 出库单号 |
| `/integration/oms/outbound/cancel` | POST | `OmsOutboundCancelCmd` | `Result<Void>` |

`OmsOutboundOrderCmd` 字段：

| 字段 | 校验 | 说明 |
| --- | --- | --- |
| `omsOrderNo` | `@NotBlank` | OMS 业务单号，幂等键 |
| `warehouseCode` | `@NotBlank` | 出库仓库编码 |
| `remark` | 可空 | 备注 |
| `items` | `@NotNull` | 明细列表；同一 SKU 不同库位必须各占一行 |
| `items[].skuCode` | `@NotBlank` | 商品编码 |
| `items[].locationCode` | `@NotBlank` | 库位编码（拣货按库位 + 批次） |
| `items[].batchNo` | 可空 | 批次号 |
| `items[].qty` | `@NotNull` 且 > 0 | 计划出库数量 |

`OmsOutboundCancelCmd` 字段：`omsOrderNo`（可空）、`wmsOutboundNo`（可空）、`reason`；两者不能同时为空。

**幂等口径**

- 幂等键为 `sourceSystem=OMS` + `externalOrderNo=omsOrderNo`，依托 `wms_external_order` 唯一键 `uk_external_order(source_system, external_order_no)`；并发下以唯一键冲突兜底，不做「先查后插」。
- 首次推送实测 `body.code=200` 并返回 WMS 出库单号；`wms_external_order` 落 `source_system=OMS`、`external_order_no=omsOrderNo`、`business_type=SALE_OUT`、`status=RECEIVED`，建单成功后回填 `outbound_no`。
- 同一 OMS 单号二次推送实测 `body.code=409`，文案逐字为 `单据已接收，请勿重复推送: {omsOrderNo}`；SQL 核对该 OMS 单号仅生成 1 张出库单。

**主数据校验规则（登记前置）**

建单前先经 `AdminApi` 校验，任一不通过返回 `body.code=400` 并指明缺失编码，且**不会在 `wms_external_order` 留下幂等登记**（实测三笔异常请求登记数为 0）：

- 仓库不存在：`仓库不存在: {warehouseCode}`
- 库位不存在：`库位不存在: {locationCode}`
- 库位不属于该仓库：`库位不属于该仓库: {locationCode}`
- 商品不存在：`商品不存在: {skuCode}`

**落库与全链路扣减（实测）**

- 出库单落 `order_type=SALE_OUT`、`source_system=OMS`、`source_no=omsOrderNo`；明细按行落库（SKU / 库位 / 批次 / 计划数量）。
- 拣货按明细行的 `locationCode` + `batchNo` 执行；确认后库存由 `wms-inventory-service` 按库位分别扣减，流水 `business_type=SALE_OUT`。对照的 CTM 回归单未产生扣减流水，两条链路互不干扰。

**取消规则（实测）**

- 建单后未拣货直接取消：生效，单据 `CANCELLED`。
- 已拣货（`PICKED` 且存在 `picked_qty>0` 明细）后取消：拒绝直接取消，`body.code=409`，文案逐字为 `已拣货不可直接取消，请走反向入库（退货/调拨退回）回补库存`；经 OMS 取消端点调用时 outbound 的 409 code/msg **原样透传**到调用方，不归一成 502；取消被拒后单据保持原状态。
- `omsOrderNo` 与 `wmsOutboundNo` 同时为空：`body.code=400`，文案 `omsOrderNo 与 wmsOutboundNo 不能同时为空`；`wmsOutboundNo` 优先，缺失时按 `omsOrderNo` 查 `wms_external_order` 回填的 `outbound_no` 解析。

**事件与回调**

- 单据终态 / 取消事件复用既有 `wms-order-status` topic，消息 `key=orderNo`（出库单号）；发件箱 `wms_event_outbox` 记录 `status=SENT`。
- OMS 状态出站回调地址取配置 `wms.integration.oms.callback-url`，**默认空**。未配置时状态通知只记 INFO「OMS 回调未配置，跳过」并返回成功，不阻断单据终态；配置后由 `RestTemplate`（连接 / 读超时各 3s）POST JSON，失败只记 WARN、返回成功。
- **OMS 回调未真实联调**：本轮 `callback-url` 保持默认空，仅验证了「未配置只记日志、不阻断终态」这一分支；已配置后的真实 HTTP 投递、重试与对账未实测。

### 6.8 静态合规核查 —— 已实测

- 9 个服务 `application.yml`：`application.name` 与模块名一致、端口为分配值且不重复、datasource 指向各自库、eureka `defaultZone` 指向 8761 —— **9/9 通过**。
- 除 inventory 外无 `InventoryMapper`、无 `com.jim.wms.inventory` import、无 `wms_inventory` 表名引用（仅 1 处 Javadoc 注释提及旧包名，非代码引用）。
- 各服务迁移脚本只建自己的表，归属与第 3 节一致；report 无业务表；数据库层表清单与脚本一致。
- 全工程无旧单体库 `jim_wms` 直连连接串。
- 4 个 `@FeignClient` 全部位于 `wms-common` 的 `api` 包；业务服务只注入 `com.jim.wms.common.api` 接口；除 CTM 出站回调外无服务间直连。

### 6.9 缺陷与观察项最终状态

详见 `docs/VERIFICATION-REPORT.md` 第 4 节。最终状态摘要：

1. **D1（误判，已更正）**：经核对 `docs/MONOLITH-BASELINE.md`（`stock-history` 全文 0 命中）与单体备份控制器，inventory 对外端点仅 `/query`、`/on-hand`、`/freeze` 三个；**新工程对外端口契约与基线逐条一致，无缺失**，亦不新增 `stock-history` 端点。
2. **D2（与单体一致，非本次引入）**：缺参 / 未知路由落入兜底 `Exception` 返回 body.code=500；备份旧单体 `wms-framework/GlobalExceptionHandler.java` 同样只有 BusinessException / 校验异常 / Exception 三类处理器，行为一致，**不构成对单体契约的突破**。
3. **D3（已修复并复测通过）**：`markSuccess` 改用 `UpdateWrapper` 显式 `set last_error=null`；本轮 TRANSFER 链路复测 SUCCESS 任务 `last_error IS NULL`。
4. **D4（已实测通过）**：integration 原样透传 `orderType`，outbound 幂等映射 `TRANSFER`/`TRANSFER_OUT → TRANSFER_OUT`；实测链路落 `TRANSFER_OUT`、流水 `business_type=TRANSFER_OUT`（见 6.7.1）。
5. **D5（部分接线，已更新）**：`AdminApi` 已暴露 warehouse/location/sku 查询，admin 侧已实现；integration 的 OMS 建单流程已注入消费 AdminApi 做仓库 / 库位 / 商品主数据校验（见 6.10）。CTM 补货 / 调拨路径仍未做主数据 Feign 校验，沿用旧单体语义。
6. **D6（非阻塞项）**：`wms-common` 的 `InventoryView.java:10` Javadoc 残留旧实体包名注释；仅注释，无 import 与编译引用，不改 common。
7. **D8（已修复，见验证报告 4 节）**：健康链路 confirm 误报 CONFIRM_FAILED、需等扫描周期才 FINISHED；修法为首次认领 + dispatch 三态 + 结束重查 + 终态幂等守卫，实测 2.303s 直接 FINISHED。
8. **D9（已修复）**：库存空批次维度不匹配导致同 SKU 同库位重复建行；入口归一空串 / 全空格为 NULL，实测三笔 +1 合并为 1 行 qty 3.000。
9. **D10（已修复）**：CTM 补货单明细无库位导致按库位拣货 404；改为库位可选、唯一可拣行直接拣、多行 400 带候选、明细与入参都无库位时按唯一可用库存库位回填。
10. **D11（已修复）**：CTM 建单未回填 `wms_external_order.outbound_no`；建单 200 后与 OMS 路径一致回填。
11. **D12（现状未改，范围外）**：调拨收货只置 FINISHED，未做调入仓入库，期间调出仓已扣、调入仓未加，账面不闭环。
12. **D13（验证限制）**：Kafka 不可用的长期 PENDING/FAILED 未取得严格隔离样本；本轮「停 Kafka」以不可达 bootstrap（9099）模拟，同库主实例 Relay 抢投兜底。

## 7. 未验证项

以下能力在本环境仍未覆盖，交付前需在具备依赖的环境中补测：

- **Kafka 真实投递与消费 —— 已实测（2026-11-18）**：broker 4.4.0 KRaft（127.0.0.1:9092）在线，默认依赖 spring-kafka 3.1.4、kafka-clients 3.6.2，协议协商正常；`wms-stock-event`、`wms-order-status` 两 topic 的生产与 `kafka-console-consumer` 消费条数、key、字段口径已核对一致（证据见验证报告 1C 节）。残留限制：broker 持续不可用、无在线兜底实例的严格隔离样本本轮未取得（见 D13）。
- **真实 CTM 回调**：`http://127.0.0.1:9000` 不存在，回调 `Connection refused`，仅记录 ERROR；未验证回调成功、重试补偿与对账。
- **OMS 回调真实联调**：`wms.integration.oms.callback-url` 默认空，本轮仅验证「未配置只记 INFO 跳过、不阻断终态」；已配置后的 HTTP 投递、失败重试与对账未实测（OMS 回调未真实联调）。
- **Eureka 高可用 / 多实例**：单注册中心、每服务单实例。
- **并发与压测范围**：仅在单库存维度做 20 并发互斥与不丢失更新验证；未做全链路压测、超卖压测、重复推送 / 重复回调压测，也未评估 9 服务同时在线的内存与端口压力。第一轮（2026-11-17）A/B 两个 20 并发的成功结果是在 CLI 覆盖 producer max.block.ms 为 50（场景 B 另覆盖 wait-timeout 为 10000ms）的临时实例上取得；题面给定的旧基线为默认配置下 Kafka 不可连时 20 并发首轮 17 个 429 / 3 个 200，本轮未在旧代码上复现。2026-11-18 改造后复测（默认配置，未覆盖参数）：Kafka 在线 20 并发 20×200、429 为 0；bootstrap 不可达（9099 模拟）20 并发仍 20×200、429 为 0（数据见验证报告 1C 与 2.9 节）。
- **stocktake / return / report / inbound 业务链路**：transfer 的在途通知与收货状态流转已在本轮 E2E 实测（见 5A.5 与报告 1C）；stocktake、return、report、inbound 本轮无业务数据，仅随启动注册与 Redis 就绪验证，其对外端点与 Feign 成功链路未做链路级 E2E。

> Redis 分布式锁已在本轮真实环境实测通过（启动注册、加解锁 token、20 并发 A/B、429 有界等待、503 快速失败、兜底开关），逐条数据与证据见 `docs/VERIFICATION-REPORT.md`「Redis 分布式锁改造与验证」一节。

## 8. 与单体版本的差异

| 维度 | 单体版本 | 微服务版本 |
| --- | --- | --- |
| 代码结构 | 单工程单进程 | 11 个 Maven 模块（common + eureka + 9 业务服务） |
| 数据库 | 单库 `jim_wms` | database-per-service，9 个独立库 |
| 服务内调用 | 进程内方法直调 | OpenFeign 经注册中心调用，契约在 wms-common |
| 跨服务事务 | 本地 `@Transactional` 覆盖多表 | 跨服务无分布式事务，改为补偿任务 + 幂等 + 定时重试 |
| 报表取数 | 直连库存表 SQL 聚合 | report 经 `InventoryApi.list` Feign 取数后内存聚合 |
| 盘点取数 | 直连库存表 | stocktake 经 Feign 取数、回写走 Feign |
| 端口归属 | 单体统一端口 | 8081–8089 分属各服务，8761 为注册中心 |
| 公共模块 | wms-framework | 重构为 `wms-common`（Result、Feign API、DTO、异常、锁、事件、工具） |
| 启动入口 | wms-bootstrap 单启动类 | 取消 bootstrap，各服务独立 `*ServiceApplication` 启动 |

## 9. 迁移步骤

### 9.1 回滚备份

- 单体基线工程与数据库备份位于 `D:\wms\wms-backend-monolith-backup`，保持不动。
- 本轮微服务版改造前备份位于 `D:\wms\wms-backend-backup-20261118`，含逐文件 MD5 清单 `MANIFEST-MD5.txt`，供本轮回滚核对。
- 回滚方式：停止微服务各进程，恢复对应备份目录工程为运行工程；数据库层面旧库 `jim_wms` 仍保留（本轮微服务未直连旧库），可直接切回单体数据源。

### 9.2 旧库数据拆分思路（jim_wms → 9 个新库）

1. 按表归属（见第 3 节）把旧库 `jim_wms` 的表导出，按目标库分别导入：
   - 主数据表 → `jim_wms_admin`；台账/流水 → `jim_wms_inventory`；入库 → `jim_wms_inbound`；出库（含补偿任务）→ `jim_wms_outbound`；调拨 → `jim_wms_transfer`；盘点 → `jim_wms_stocktake`；退货 → `jim_wms_return`；外部单据登记 → `jim_wms_integration`；report 不迁业务表。
2. 迁移前核对库存台账与流水一致，冻结未结单据；迁移后按 `仓库+库位+SKU+批次` 复核在库数量。
3. 流水表 `biz_key`（幂等键）随历史数据保留；新链路扣减/上架必须携带幂等键。
4. 先在影子库完成导入与对账，再切流量。

### 9.3 切流顺序

1. 部署并启动 MySQL（9 库）、Redis、Kafka。
2. 先起 `wms-eureka-server`，再起 admin、inventory 底座。
3. 依次起 inbound、outbound、integration、report、stocktake、return、transfer。
4. 校验 Eureka 实例全部 UP、主数据与库存对账一致后，把外部入口（OMS / CTM / PDA）流量切到对应服务端口。
5. 观察补偿任务、回调与事件日志；异常时按 9.1 回滚。

## 10. 最终一致性风险点

| # | 风险点 | 触发条件 | 检测方式 | 当前缓解手段 | 残留风险 |
| --- | --- | --- | --- | --- | --- |
| 1 | 出库扣减失败后长期未补齐库存 | 扣减返回库存不足 / inventory 短暂不可用，补货未及时 | 查 `wms_outbound_stock_task` 中 FAILED、retry_count 接近 5 的任务 | 定时任务每 60s 重试，最多 5 次；inventory 按 biz_key 幂等 | 超过 5 次仍 FAILED 需人工介入；无自动告警闭环 |
| 2 | 重试导致重复扣减 | 补偿任务重复触发、网络重试 | 对账库存流水 OUT 条数与出库明细 | inventory 按 `biz_key` 幂等，重复请求直接返回当前库存 | 幂等键生成规则若被破坏仍可能重复写 |
| 3 | 库存与出库单状态短暂不一致 | 扣减成功但回写出库单 / 回调中断 | 对比出库单终态与台账增减 | 补偿任务表记录每次扣减结果，终态按任务全部 SUCCESS 推进 | 本地单据与库存存在秒级~分钟级不一致窗口 |
| 4 | CTM 回调丢失 | 9000 不可用或回调网络中断 | integration 回调失败记录、外部单据状态 | 回调失败记 ERROR 待补偿；任务/单据状态以 WMS 为准 | 本轮未验证回调重试与对账补偿，可能长时间不一致 |
| 5 | Redis 不可用 | Redis 宕机 / 连接超时 | 启动自检 ERROR「Redis 不可用，加锁操作将快速失败(503)」；请求 body.code=503 | 默认抛 503 快速失败，不静默降级；仅 `wms.lock.allow-local-fallback=true` 显式打开时才回退进程内锁（默认 false） | 若误在多实例 / 生产打开兜底，进程内锁互斥失效，并发变更同一维度会丢失更新、存在超卖风险 |
| 6 | 发件箱事件积压 / 投递失败未达上限前滞留 | Broker 不可用、消费者宕机；Relay 线程崩溃 | `wms_event_outbox` 中 PENDING/FAILED 行数、last_error、retry_count；topic 积压 | 业务事务内同事务落发件箱记录；Relay 条件 UPDATE 认领、失败按 backoff 顺延、SENDING 租约过期回收；达到 `max-retry=20` 后置 FAILED 不再自动重试 | 积压期间下游镜像/对账为滞后视图；达到上限后需人工介入重投；消费者侧积压与对账本轮未覆盖 |
| 7 | 跨服务主数据校验缺失 | 入库/出库传入不存在的仓/库位/SKU | 主数据 Feign 核对 | OMS 建单路径已注入 AdminApi 做仓库/库位/商品校验；CTM 补货/调拨路径仍未走主数据 Feign 校验，沿用旧单体语义 | CTM 路径非法主数据可能落到业务单 |
| 8 | 取消单被补偿任务复活 | 取消与定时重试并发 | 已 CANCELLED 单据/任务被再次扣减 | 取消时未成功任务置 CANCELLED，重试扫描跳过 CANCELLED | 取消与重试并发窗口内仍需以数据库状态为准复核 |
| 9 | 锁租约到期而业务未完成，锁提前失效 | 单次临界区业务耗时超过 `lease-time`（默认 30s） | 观测单请求耗时、锁 TTL 与异常并发进入临界区 | 当前业务耗时远小于 30s；租约必须大于最长业务耗时，业务耗时变长需同步上调 `wms.lock.lease-time` | 若业务卡死超过租约，锁提前释放，其他线程/实例可能并发进入临界区，存在丢失更新窗口 |
| 10 | Redis 重启丢失在持锁（`appendonly=no`） | Redis 未开 AOF，宕机/重启后内存 key 清空 | 重启后 `DBSIZE`、在持锁 key 归零 | 锁租约有界（`lease-time=30s`），最坏情况为锁提前过期而非无限持有；重启后新请求重新加锁 | 持锁期间 Redis 重启，正在执行的临界区失去互斥保护；如需更强恢复保证，生产可评估开启 AOF / RDB 持久化 |
| 11 | 认领租约与多实例 | 认领实例崩溃、长时间 GC、实例间时钟漂移 | PROCESSING 任务 `lease_until`、owner；候选扫描是否回收 | 租约 60s，过期 PROCESSING 由其它实例 `claimExpired` 回收；终态回写清空 owner/lease | 租约按最坏耗时（锁等待 2s 加 Feign 读超时 10s，约 12s）的约 5 倍估算；若单次耗时上调需同步上调租约；`lease-renew-interval-ms` 为预留项，当前仅 Feign 前后各续租一次，未做周期续租（见 5A.4） |
| 12 | 调拨在途通知 404 / 离线兜底 | transfer 服务离线、调拨单号不匹配 | 出库单 `last_error`；transfer 单状态 | TRANSFER_OUT 全部 SUCCESS 后在业务事务外调 `TransferApi.markInTransit`；失败只记 WARN + 单据级 `last_error`，单据保持 FINISHED，不阻断出库 | 通知失败期间调拨单停留在 CREATED，需人工或后续补扫核对；404 报文分支已实测，连接异常分支与 404 同走兜底记录 |
| 13 | 单据维度补扫窗口 | finalize 前进程崩溃、任务与单据状态脱节 | 停留在 CONFIRMING 超过 `doc-reconcile-threshold-ms=120000` 的单据 | 定时补扫重查任务、按 requestId 补缺口、全 SUCCESS 推终态；仍有未成功回 CONFIRM_FAILED 交任务级重试 | 120s 窗口内单据状态对调用方为滞后视图；补扫不直接调 Feign，扣减仍由任务级扫描完成 |
| 14 | 调拨收货不做调入仓入库（账面缺口） | 调入仓无库存增加 | 调入仓库存台账与调拨单数量对账 | `receive` 仅把调拨单 IN_TRANSIT 置 FINISHED；实物入库未接入 inventory 写者（现状，范围外，见 D12） | 调出仓已扣、调入仓未加，期间库存分布账面不闭环；需后续由调入收货流程经 InventoryApi 回补 |
| 15 | 跨实例时钟与并发认领 | 多实例时钟不一致、并发确认同一单 | 认领 UPDATE 影响行数、任务 owner | 条件 UPDATE 单条认领影响 1 行才算抢到；`claimFirstAttempt` 仅认 PENDING 且 retry_count=0；终态幂等守卫重复推终态 | 本轮双实例短时并发已测；长稳多实例压测、时钟漂移边界未压测 |

## 11. 目录结构

```
wms-backend/
├── pom.xml                         # 父 POM：Spring Boot 3.2.5 / Cloud 2023.0.2 / JDK 17
├── wms-common/                     # 公共模块：Feign API、DTO、Result、异常、锁、事件、工具
│   └── src/main/java/com/jim/wms/common/
│       ├── api/                    # AdminApi / InventoryApi / OutboundApi / IntegrationApi
│       ├── dto/  event/  exception/  lock/  result/  util/  config/
├── wms-eureka-server/              # 注册中心 :8761
├── wms-admin-service/              # 主数据服务 :8081
├── wms-inventory-service/          # 库存服务 :8082（库存唯一写者）
├── wms-inbound-service/            # 入库服务 :8083
├── wms-outbound-service/           # 出库服务 :8084（含补偿任务）
├── wms-transfer-service/          # 调拨服务 :8085
├── wms-stocktake-service/          # 盘点服务 :8086
├── wms-return-service/             # 退货服务 :8087（包名 com.jim.wms.returns）
├── wms-report-service/             # 报表服务 :8088
├── wms-integration-service/        # CTM 集成服务 :8089
└── docs/
    ├── ARCHITECTURE-SPEC.md        # 架构规范：端口 / 库名 / 表归属 / Feign 契约 / 补偿流程
    ├── MONOLITH-BASELINE.md        # 单体基线：28 个对外端点契约
    └── VERIFICATION-REPORT.md      # 本阶段端到端验证报告（逐条结论 + 证据索引 + 缺陷）
```

## 12. 变更记录与文档索引

### 12.1 变更记录

- 单体 WMS 拆分为 11 个 Maven 模块；公共能力收敛到 `wms-common`。
- 单库 `jim_wms` 拆分为 9 个业务库，服务独占库表。
- 进程内调用改为 OpenFeign；跨服务一致性改为补偿任务 + 幂等 + 定时重试。
- 报表、盘点去直连库存表，改为 Feign 取数。
- 本阶段完成全量构建、6 服务联调、入库/出库成功链路、补偿闭环、CTM 契约、报表聚合与静态合规核查。
- 返工回归：integration 原样透传外部 `orderType`（不再预映射）；outbound 建单类型幂等映射 `TRANSFER`/`TRANSFER_OUT → TRANSFER_OUT`，其余 → REPLENISH；`markSuccess` 显式置空 `last_error`。返工后重跑全量构建（12/12 SUCCESS）与 CTM TRANSFER 调拨链路 E2E，复测 409 幂等、400 空号、带号取消不复活。
- 新增 OMS 销售出库入口：`/integration/oms/outbound/order`、`/integration/oms/outbound/cancel`；服务端固定 `SALE_OUT`，OMS 单号幂等（`uk_external_order`），建单前走 AdminApi 主数据校验，PICKED 拒绝直接取消；终态事件复用 `wms-order-status`，OMS 回调地址 `wms.integration.oms.callback-url` 默认空（未真实联调）。
- 事件发布改为事务性发件箱：`wms-common\outbox` 内核（OutboxWriter / OutboxRelay / OutboxProperties），inventory / inbound / outbound 三处接入；业务事务内落 PENDING 记录，Relay 在事务外投递并标记 SENT / FAILED，事件移出库存锁临界区。
- 出库补偿任务三态认领与租约：任务表新增 PROCESSING / owner / lease_until；条件 UPDATE 认领影响 1 行才算抢到，认领时 retry_count+1；过期租约回收；终态清空 owner/lease；认领独立事务提交，Feign 在事务外。
- PENDING 超时纳入扫描：候选覆盖 PENDING/FAILED 到期与过期 PROCESSING；next_retry_time 由应用时钟计算，与 create_time 同一时间来源。
- 多库位多批次拣货：明细唯一性下沉到 `(outbound_no, sku_code, location_code, batch_key)` 生成列唯一键；requestId 含库位与批次，与库存侧 biz_key 幂等口径一致；保留不得超过计划量校验。
- PICKED 禁止直接取消：存在 picked_qty>0 明细时取消返回 409，提示走反向入库回补库存；未拣货单据仍可取消。
- 调拨在途打通：`TransferApi` 新增 markInTransit / receive 契约；TRANSFER_OUT 全 SUCCESS 后通知 transfer 置在途，调入收货置 FINISHED；通知失败只记 WARN 与单据级 last_error。
- 单据维度补扫：CONFIRMING 超 120s 的单据重查任务、补缺口、推终态，覆盖 finalize 前进程崩溃窗口。
- 缺陷回归修复：空批次归一 NULL 避免重复建行；CTM 明细无库位可按唯一可用库存库位回填；CTM 建单回填 outbound_no；健康链路 confirm 首次认领消除误报 CONFIRM_FAILED（详见验证报告 D8–D13）。

### 12.2 文档索引

| 文档 | 位置 | 内容 |
| --- | --- | --- |
| 架构规范 | `docs/ARCHITECTURE-SPEC.md` | 端口、库名、表归属、Feign 契约、补偿与降级流程 |
| 单体基线 | `docs/MONOLITH-BASELINE.md` | 28 个对外端点契约与字段口径 |
| 验证报告 | `docs/VERIFICATION-REPORT.md` | 逐条验证结论、原始证据路径、缺陷清单 |
