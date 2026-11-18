# wms-outbound-service 改造交付报告（2026-11-18）

契约：`docs/REFACTOR-SPEC-2026-11-18.md`。仅改动 `wms-outbound-service`；未改 wms-common、其它 8 个服务、V1 迁移、Redis 锁、topic 名与 key 规则、库存侧幂等语义。

## 1. 改动文件清单与行号

### 新增
- `wms-outbound-service/src/main/resources/db/migration/V2__task_lease_and_item_dim.sql`（全文件）
- `wms-outbound-service/src/main/resources/db/migration/V3__event_outbox.sql`（全文件）
- `wms-outbound-service/src/main/java/com/jim/wms/outbound/config/OutboundProperties.java`（全文件，`wms.outbound.*` 绑定）

### 修改
- `entity/OutboundStockTask.java`：新增 `owner`（L47-48）、`leaseUntil`（L50-51）字段。
- `entity/OutboundOrder.java`：新增 `lastError` 单据级错误列（L43-44）。
- `mapper/OutboundStockTaskMapper.java`：新增 `selectCandidates / claimNew / claimExpired / renewLease / markSuccessTerminal / markFailedTerminal / markCancelledTerminal` 七个方法（全文）。
- `service/OutboundService.java`：`pick` 签名增加 `batchNo`（L15）。
- `controller/OutboundController.java`：`PickRequest` 增加 `batchNo` 字段（L56），`/pick` 透传批次（L33）。
- `service/impl/OutboundServiceImpl.java`：整体重写，关键行号见第 3/5/6/7/8 节。
- `OutboundServiceApplication.java`：加 `@EnableConfigurationProperties(OutboundProperties.class)`。
- `resources/application.yml`：Feign 超时改 kebab 显式配置；新增 `wms.outbox.*`、`wms.outbound.*` 两块。

### 删除
- `producer/OrderStatusProducer.java`（整类删除，目录已移除）。Grep 确认源码内无 `KafkaTemplate / OrderStatusProducer / sendOrderStatus / kafkaTemplate` 残留。

### 契约外补充（需 OrganizeAgent 知悉）
- V2 额外给 `wms_outbound_order` 加了 `last_error VARCHAR(500)`：规格第 7 节要求「404 只记 WARN + 单据级 last_error」，原 V1 单据表无此列；未改 V1，在 V2 追加。

## 2. V2 / V3 迁移全文

### V2__task_lease_and_item_dim.sql
```sql
-- V2: 补偿任务三态认领与租约 + 明细唯一性下沉（规格 REFACTOR-SPEC-2026-11-18 第 4/5 节）
ALTER TABLE wms_outbound_stock_task
    ADD COLUMN owner VARCHAR(64) DEFAULT NULL COMMENT '认领者：实例:端口:线程',
    ADD COLUMN lease_until DATETIME DEFAULT NULL COMMENT '认领租约到期时间',
    ADD KEY idx_status_lease (status, lease_until);

ALTER TABLE wms_outbound_item
    ADD COLUMN batch_key VARCHAR(64) GENERATED ALWAYS AS (IFNULL(batch_no, 'NO_BATCH')) STORED,
    ADD UNIQUE KEY uk_item_dim (outbound_no, sku_code, location_code, batch_key);

ALTER TABLE wms_outbound_order
    ADD COLUMN last_error VARCHAR(500) DEFAULT NULL COMMENT '单据级最近一次失败原因（如调拨在途 404），供补扫排查';
```

### V3__event_outbox.sql
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

空库执行，`uk_item_dim` 建索引前表内 0 行，无重复数据阻塞；Flyway 历史：V1 success / V2 success / V3 success（见 `05-flyway-history.txt`）。

## 3. 认领 / 候选 / 续租 / 终态回写 SQL 与所在方法

均在 `mapper/OutboundStockTaskMapper.java`，调用方在 `service/impl/OutboundServiceImpl.java`。

### 候选查询（`selectCandidates`，扫描器 `sweepCompensation` L254）
```sql
SELECT * FROM wms_outbound_stock_task
WHERE (status IN ('PENDING','FAILED') AND retry_count < #{maxRetry}
       AND (next_retry_time IS NULL OR next_retry_time <= #{now}))
   OR (status = 'PROCESSING' AND lease_until IS NOT NULL AND lease_until < #{now})
ORDER BY id ASC
```
同时命中「PENDING/FAILED 到期」与「PROCESSING 租约过期回收」。

### 认领新任务（`claimNew`，`dispatchTask` L350，独立事务 `claimTemplate` REQUIRES_NEW L77-78）
```sql
UPDATE wms_outbound_stock_task
SET status='PROCESSING', owner=#{owner}, lease_until=#{leaseUntil},
    retry_count = retry_count + 1, update_time=#{now}
WHERE id=#{id} AND status IN ('PENDING','FAILED') AND retry_count < #{maxRetry}
  AND (next_retry_time IS NULL OR next_retry_time <= #{now})
```

### 回收过期 PROCESSING（`claimExpired`，同 `dispatchTask`）
```sql
UPDATE wms_outbound_stock_task
SET status='PROCESSING', owner=#{owner}, lease_until=#{leaseUntil},
    retry_count = retry_count + 1, update_time=#{now}
WHERE id=#{id} AND status='PROCESSING' AND lease_until < #{now}
  AND retry_count < #{maxRetry}
```

### 续租（`renewLease`，`renewLease` L590，仅 owner 匹配）
```sql
UPDATE wms_outbound_stock_task
SET lease_until=#{leaseUntil}, update_time=#{now}
WHERE id=#{id} AND owner=#{owner}
```

### 终态回写
SUCCESS（`markSuccessTerminal`，`markSuccess` 路径 L417-419）：
```sql
UPDATE wms_outbound_stock_task
SET status='SUCCESS', owner=NULL, lease_until=NULL, last_error=NULL,
    next_retry_time=NULL, update_time=#{now}
WHERE id=#{id}
```
FAILED（`markFailedTerminal`，`recordFailure` L436）：
```sql
UPDATE wms_outbound_stock_task
SET status='FAILED', owner=NULL, lease_until=NULL, last_error=#{lastError},
    retry_count=#{retryCount}, next_retry_time=#{nextRetryTime}, update_time=#{now}
WHERE id=#{id}
```
CANCELLED（`markCancelledTerminal`，cancel L196 / 单据已取消回收 L358）：
```sql
UPDATE wms_outbound_stock_task
SET status='CANCELLED', owner=NULL, lease_until=NULL, update_time=#{now}
WHERE id=#{id}
```

认领在 `claimTemplate`（PROPAGATION_REQUIRES_NEW）内提交后才出事务做 Feign；Feign 前后各 `renewLease` 一次。

## 4. 发件箱改写位置与事务边界

- 旧 `OrderStatusProducer.sendOrderStatus` 两处调用点全部替换为 `outboxWriter.write(WmsEventTopics.TOPIC_ORDER_STATUS, orderNo, payload)`：
  1. `cancel` L200-205：cancel 方法本身 `@Transactional`，发件箱写在 `txTemplate` 业务事务内（单据 CANCELLED + 发件箱同事务提交）。
  2. `finalizeIfAllSuccess` L481-489：单据置 FINISHED/PICKED 与 `outboxWriter.write` 在同一个 `txTemplate` 事务内；事务提交后才 `notifySource` / `notifyTransferInTransit`。
- payload 字段名与旧单体一致：`orderNo / status / message`（`buildOrderStatusPayload` L641）。
- 未在任何库存锁临界区调用 Kafka；本服务不再持有 `KafkaTemplate` Bean 引用。
- yml `wms.outbox.enabled=true`，7 项参数照规格第 1 节；`OutboxRelay` 由 common 自动装配事务外投递。

## 5. 多库位多批次拣货 / 确认 / requestId

- 拣货定位：`requireItemDim` L609，按 `outbound_no + sku_code + location_code + batch_no`（批次空按 IS NULL 匹配）定位唯一行；`pickedQty + qty > planQty` → 400（L115-118）。
- 确认落任务：`insertPendingTask` L570，每个明细行一条任务，`locationCode/batchNo` 取该行实际库位批次；`changeQty = pickedQty.negate()`。
- 扣减：`deductStock` L408，`StockChangeCmd` 的 `locationCode/batchNo` 取自任务行，各行扣各自库位。
- requestId（`buildRequestId` L635）：
  `OUT:{outboundNo}:{skuCode}:{locationCode}:{batchNo 或 NOBATCH}`
  同单同 SKU 不同库位/批次 → 不同 requestId（真机证据见第 9 节，LOC-7:B2 与 LOC-9:NOBATCH 两条）。

## 6. PICKED 取消 409 判定

`cancel` L176-194：
```java
boolean anyPicked = items.stream()
        .anyMatch(i -> nvl(i.getPickedQty()).compareTo(BigDecimal.ZERO) > 0);
if ("PICKED".equals(order.getStatus()) && anyPicked) {
    throw new BusinessException(409, "已拣货不可直接取消，请走反向入库（退货/调拨退回）回补库存");
}
```
全部未拣 → 允许取消；SUCCESS 任务不回滚，非 SUCCESS 任务置 CANCELLED 并清 owner/lease。

## 7. 调拨在途 markInTransit

- 位置：`finalizeIfAllSuccess` L491-495，仅 `orderType=TRANSFER_OUT && status=FINISHED` 后调 `notifyTransferInTransit` L503。
- 入参：`TransferInTransitCmd{transferNo = order.sourceNo, outboundNo}`（L506-508）。
- 失败处理：Feign 异常或非 200（含调拨侧 404）只记 WARN + `recordOrderLastError`（L525，独立短事务写单据级 `last_error`），不改变单据 FINISHED 状态、不让单据失败；调用在业务事务之外。

## 8. 单据维度补扫

- 定时方法 `reconcileConfirmingOrders` L283：扫描 `status='CONFIRMING' AND update_time < now - doc-reconcile-threshold-ms(120000)`。
- `reconcileOne` L305：
  1. 查已拣明细与现有任务；事务内按 requestId 补建缺失 PENDING 任务（`DuplicateKeyException` 兜底幂等）；
  2. 重载任务：全部 SUCCESS → `finalizeIfAllSuccess`（FINISHED/PICKED + 发件箱事件）；
  3. 仍有未成功 → `markOrderConfirmFailed` 回 CONFIRM_FAILED 交任务级重试。
- 补扫本身不直接调 Feign，扣减仍由任务级扫描完成；requestId 唯一键保证不重复扣减。

## 9. 真机自验证据（18084，未起 8082/8761，未依赖 inventory/transfer 在线）

构建：`mvn -B -DskipTests -pl wms-outbound-service package` → BUILD SUCCESS。启动：`java -jar ... --server.port=18084 --eureka.client.enabled=false`，Tomcat 18084，Flyway 迁移 V2/V3 成功。

| 项 | 结果 | 证据文件 |
|---|---|---|
| a 认领唯一性 | 同一 id 两条条件 UPDATE：claim1=1 行，claim2=0 行 | `a-claim-uniqueness.txt` |
| b retry_count 认领自增 + 上限截断 | 认领前 0 → 认领后 1；retry_count=5 的 FAILED 再认领 0 行 | `a-claim-uniqueness.txt` |
| c 租约过期回收（SQL 层） | 过期 PROCESSING 回收 1 行、owner 变更、retry 2→3；未过期 0 行不动 | `c-lease-reclaim.txt` |
| c 租约过期回收（扫描器层） | 调度线程 `claimExpired` Updates:1（id 11、12），owner 由 old-instance 变为 `wms-outbound-service:18084:scheduling-1`，retry 递增；随后 Feign 离线 → FAILED 清 owner/lease | 运行日志 `.sessions-outbound-run.log` 11:25:28/11:25:29；`03-tasks-before-cleanup.txt` |
| d 终态清空 | SUCCESS 回写后 owner/lease_until/last_error/next_retry_time 均 NULL | `d-terminal-writeback.txt` |
| e PENDING 超时 + 应用时钟 | 任务 create_time=`11:22:58.368940700`，next_retry_time=`11:23:08.368940700`，差值 10000ms（pending-threshold-ms=10000），同一 `now` 来源；到期后扫描器拾起 | 运行日志 insert Parameters 行；`03-tasks-before-cleanup.txt`（id 13/14/16） |
| 多库位多批次 | 同单 SKU-E 两行：LOC-9/NO_BATCH、LOC-7/B2；两条任务、两个 requestId；超拣 1 件 → 400「拣货数量超过计划数量」 | `02-items-before-cleanup.txt`、`03-tasks-before-cleanup.txt`、`pick3-over.json` 响应 |
| 409 取消 | PICKED + 已拣 → `code=409`，文案「已拣货不可直接取消，请走反向入库（退货/调拨退回）回补库存」，单据状态不变；全未拣取消 → 200 CANCELLED | 运行响应；`01-orders-before-cleanup.txt`、`04-outbox-before-cleanup.txt` |
| 发件箱 | CANCELLED / FINISHED 两条 PENDING 落库，Relay 投递后 SENT；payload 含 orderNo/status/message；event_key=orderNo | `04-outbox-before-cleanup.txt` |
| 补扫-补缺口 | 缺任务的 CONFIRMING 单被补建 PENDING（requestId `...:SKU-G:LOC-4:NOBATCH`），仍有未成功 → CONFIRM_FAILED | `03/01` 快照 + 日志 11:26:32 |
| 补扫-推终态 | 任务全 SUCCESS 的 CONFIRMING 单 → FINISHED，发 FINISHED 出库完成事件并 SENT | `01/04` 快照 + 日志 11:26:33 |
| 调拨在途兜底 | TRANSFER_OUT FINISHED 后调 markInTransit；transfer 离线抛 RetryableException，WARN + 单据级 last_error，单据保持 FINISHED | `01-orders-before-cleanup.txt`（last_error 列）+ 日志 11:26:35 |

扫描间隔本轮使用默认 30000ms（未用命令行覆盖）；每轮日志含候选/认领/回写计数（`sweepCompensation` L254-271）。

## 10. 收尾

- 进程 PID 32152（已核对命令行为本服务 jar）已停止；18084 已释放；未误杀 Kafka（PID 29640）与 IDE LSP。
- 冒烟数据已清空：order/item/task/outbox 计数均 0；Flyway `flyway_schema_history` 保留 V1/V2/V3 三条 success；admin 种子不在本库，未触碰。
- 证据目录：`D:\wms\wms-backend\.sessions-evidence\`；运行日志 `.sessions-outbound-run.log`、构建日志 `.sessions-outbound-build.log`。

## 11. 仍未验证项（留给后续验证 agent）

- 完整 Eureka 注册/发现链路（本轮 `eureka.client.enabled=false`）。
- 与 wms-inventory-service、wms-transfer-service 真实联调：扣减成功幂等命中、调拨侧 404 分支（本轮 transfer 离线走的是连接异常分支，404 同走 `recordOrderLastError`，未拿到真实 404 报文）、`/receive` 收货置 FINISHED。
- 多实例并发压测、租约竞争与续租时序压力验证。
- `wms.outbound.lease-renew-interval-ms` 为预留配置项：当前 Feign 单次调用前后各续租一次，未按该间隔做周期续租；如需长任务周期续租可后续接入。
