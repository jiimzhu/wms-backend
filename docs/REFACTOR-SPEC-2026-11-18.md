# 本轮改造冻结规格（2026-11-18）

本文件是本轮改造的**唯一契约**。并行改造的各方按此实现，不得自行改契约；发现契约不足必须回报，不得私自变更。

## 0. 已完成、不可再改的部分（由 OrganizeAgent 冻结）

wms-common 已新增并构建通过：

- `com.jim.wms.common.outbox.OutboxProperties`（`wms.outbox` 前缀）
- `com.jim.wms.common.outbox.OutboxWriter`：`write(String topic, String eventKey, Object payload)`，在**当前事务内** INSERT 一条 PENDING 记录，失败抛 `IllegalStateException` 让业务事务回滚
- `com.jim.wms.common.outbox.OutboxRelay`：`@Scheduled` 投递线程，扫描 PENDING/FAILED + SENDING 租约过期，条件 UPDATE 认领（影响 1 行才发），成功后置 SENT，失败累加 retry_count + last_error + 顺延 next_retry_time
- `com.jim.wms.common.outbox.OutboxAutoConfiguration`：`wms.outbox.enabled=true` 才装配，并带 `@EnableScheduling`
- `com.jim.wms.common.api.TransferApi`（`@FeignClient(name="wms-transfer-service", path="/internal/transfer")`）
  - `POST /in-transit`，body `TransferInTransitCmd{transferNo, outboundNo}` → `Result<TransferView>`
  - `POST /receive`，body `TransferReceiveCmd{transferNo, remark}` → `Result<TransferView>`
- `TransferView{transferNo, fromWarehouseCode, toWarehouseCode, skuCode, batchNo, qty, status}`

`wms-common` 已 `mvn -pl wms-common -am -DskipTests install` 通过，其它模块直接依赖即可。

## 1. 发件箱表 DDL（各事件生产方各建一张，位于各自库）

inventory 库（`jim_wms_inventory`）与 outbound 库（`jim_wms_outbound`）各一份，文件名 `V2__event_outbox.sql`，内容完全一致：

```sql
CREATE TABLE IF NOT EXISTS wms_event_outbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL,
    topic VARCHAR(64) NOT NULL,
    event_key VARCHAR(128) NOT NULL,
    payload LONGTEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / SENDING / SENT / FAILED',
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_time DATETIME DEFAULT NULL,
    last_error VARCHAR(500) DEFAULT NULL,
    owner VARCHAR(64) DEFAULT NULL,
    lease_until DATETIME DEFAULT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    sent_time DATETIME DEFAULT NULL,
    UNIQUE KEY uk_event_id (event_id),
    KEY idx_status_next_retry (status, next_retry_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务性发件箱';
```

配置（两个生产方 yml 都加）：

```yaml
wms:
  outbox:
    enabled: true
    scan-interval-ms: 1000
    batch-size: 50
    max-retry: 20
    send-timeout-ms: 5000
    backoff-ms: 5000
    sending-lease-ms: 60000
```

其他服务不要打开 `wms.outbox.enabled`（默认 false，没有该表也不会启动失败）。

## 2. 事件规则（不得改）

- topic 名固定：`wms-stock-event`、`wms-order-status`（`WmsEventTopics` 常量）。
- key：库存事件 `warehouseCode:skuCode`；单据状态事件 `orderNo`。
- 库存事件字段口径与 `wms_inventory_log` 完全一致：`businessNo / changeQty / beforeQty / afterQty`（另有 eventId/eventType/warehouseCode/locationCode/skuCode/batchNo/eventTime）。
- 事件必须在**业务事务内**经 `OutboxWriter` 落库，**不得**在库存锁临界区内调用 `KafkaTemplate`。
- 幂等命中（biz_key 已存在）不得写事件、不得写发件箱。

## 3. 时间来源统一

所有 DATETIME 字段一律由应用 `LocalDateTime.now()` 显式写入（含 create_time / update_time / next_retry_time / lease_until），不再依赖数据库 `CURRENT_TIMESTAMP` 默认值，避免应用时钟与数据库时钟混用。

## 4. 出库补偿任务三态与租约（出库服务）

V2 迁移（`wms-outbound-service`，文件名 `V2__task_lease_and_item_dim.sql`）至少包含：

```sql
ALTER TABLE wms_outbound_stock_task
    ADD COLUMN owner VARCHAR(64) DEFAULT NULL COMMENT '认领者：实例:端口:线程',
    ADD COLUMN lease_until DATETIME DEFAULT NULL COMMENT '认领租约到期时间',
    ADD KEY idx_status_lease (status, lease_until);

ALTER TABLE wms_outbound_item
    ADD COLUMN batch_key VARCHAR(64) GENERATED ALWAYS AS (IFNULL(batch_no, 'NO_BATCH')) STORED,
    ADD UNIQUE KEY uk_item_dim (outbound_no, sku_code, location_code, batch_key);
```

- status 取值：`PENDING / PROCESSING / SUCCESS / FAILED / CANCELLED`（列是 VARCHAR，无需改类型）。
- 认领 SQL 形态（必须是单条条件 UPDATE，影响 1 行才算抢到）：

```sql
UPDATE wms_outbound_stock_task
SET status='PROCESSING', owner=#{owner}, lease_until=#{now+lease}, retry_count=retry_count+1, update_time=#{now}
WHERE id=#{id} AND status IN ('PENDING','FAILED') AND retry_count < #{maxRetry}
  AND (next_retry_time IS NULL OR next_retry_time <= #{now})
```

- 认领必须在自己独立的事务里提交（`REQUIRES_NEW` 或单独 TransactionTemplate），**绝不能**把 Feign 远程调用包进同一个事务。
- 候选查询必须同时命中：`PENDING/FAILED 且 next_retry_time<=now` 与 `PROCESSING 且 lease_until<now`（回收过期认领），尽量走 `idx_status_retry` / `idx_status_lease`。
- 终态回写（SUCCESS / FAILED / CANCELLED）必须清空 `owner`、`lease_until`，SUCCESS 时同时 `last_error=NULL`、`next_retry_time=NULL`。
- 长耗时步骤要续租：`UPDATE ... SET lease_until=#{now+lease} WHERE id=? AND owner=#{owner}`，仅自己持有才续租。
- 配置项（`wms.outbound`）：

```yaml
wms:
  outbound:
    lease-seconds: 60           # 租约：最坏耗时（锁等待 2s + Feign 读超时 10s + 余量）约 12s，取 ~5 倍
    scan-interval-ms: 30000     # 扫描间隔，必须显著小于 lease
    pending-threshold-ms: 10000 # 插入 PENDING 时 next_retry_time = 创建时间 + 该值
    doc-reconcile-threshold-ms: 120000 # 单据停在 CONFIRMING 超过该值触发单据维度补扫
    max-retry: 5
    lease-renew-interval-ms: 20000
```

- 出库服务 yml 必须显式设置 Feign 读超时，使最坏耗时可控：

```yaml
spring:
  cloud:
    openfeign:
      client:
        config:
          default:
            connect-timeout: 3000
            read-timeout: 10000
```

## 5. 明细维度（缺陷一）

- 明细唯一性按 `(outbound_no, sku_code, location_code, batch_no)`（DDL 用生成列 batch_key 兜住 NULL）。
- pick 入参按「SKU + 库位 + 批次」定位到唯一一行，累加 picked_qty，并回写该行实际库位/批次；`picked_qty` 不得超过 `plan_qty`（超出返回 400）。
- 确认时按每个明细行各自扣对应库位的库存。
- requestId 规则固定为 `OUT:{outboundNo}:{skuCode}:{locationCode}:{batchNo 或 NOBATCH}`（沿用现有 NOBATCH 字面，保持与库存侧 biz_key 幂等口径一致），同一单据同一 SKU 不同库位/批次必须生成不同 requestId。

## 6. PICKED 取消（缺陷二）

- 单据 status=PICKED 且存在 `picked_qty > 0` 的明细 → 直接取消返回 `body.code=409`，文案含「已拣货不可直接取消，请走反向入库（退货/调拨退回）回补库存」。
- 全部明细 `picked_qty = 0` → 允许取消（视为未拣部分关闭）。
- 不得出现「单据 CANCELLED 但已扣库存未回滚」。

## 7. 调拨在途（缺陷四）

- 出库单 `order_type=TRANSFER_OUT` 全部扣减任务 SUCCESS、单据置 FINISHED 后，调用 `TransferApi.markInTransit(transferNo = 单据 source_no（CTM 单号）, outboundNo)`。
- 调拨服务解析：先按 `transfer_no` 匹配，未命中再按 `source_no` 匹配；命中则 `CREATED → IN_TRANSIT`，已是 IN_TRANSIT/FINISHED 直接返回当前状态（幂等）。
- 调拨服务未命中该调拨单 → 返回 `body.code=404`；出库侧对 404 只记 WARN + 写入单据级 `last_error` 供补扫重试，**不得**让出库单据失败。
- `/receive` 置 `FINISHED`（幂等）。
- 调拨单 V2 迁移：`ALTER TABLE wms_transfer_order ADD COLUMN outbound_no VARCHAR(64) DEFAULT NULL, ADD KEY idx_outbound_no (outbound_no);`

## 8. 单据维度补扫

- 扫描 `status='CONFIRMING'` 且 `update_time < now - doc-reconcile-threshold-ms` 的出库单，不论任务处于何种状态，都重跑一次「查任务、补缺口、推终态」：任务缺失则补建、SUCCESS 齐则 FINISHED 并推单据状态事件、仍有失败则回 CONFIRM_FAILED 等下一次任务级重试。
- 该扫描必须幂等，重复执行不产生重复扣减（requestId 唯一键兜底）。

## 9. 边界（不得突破）

- 库存实物唯一写者是 wms-inventory-service；流水只新增。
- 不跨库 JOIN、不跨库直连。
- CTM 对外契约（`/integration/ctm/replenish/order`、`/integration/ctm/cancel`，路径/字段/`Result<String>` 单号/409/400 文案）不变。
- Kafka topic 名、key 规则不变。
- 端口、库名、Eureka 服务名不变。
- Redis 锁语义（token / Lua 释放 / 429 / 503 / 默认不降级）不变。
