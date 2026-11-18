# wms-transfer-service 调拨在途/收货内部接口改造报告（2026-11-18）

## 1. 改动文件清单（新增 / 修改）

新增：
- `wms-transfer-service/src/main/resources/db/migration/V2__transfer_outbound_link.sql`（V2 迁移）
- `wms-transfer-service/src/main/java/com/jim/wms/transfer/controller/InternalTransferController.java`（内部控制器）

修改：
- `wms-transfer-service/src/main/java/com/jim/wms/transfer/entity/TransferOrder.java:31` — 新增 `private String outboundNo;`
- `wms-transfer-service/src/main/java/com/jim/wms/transfer/service/TransferService.java` — 新增 `TransferView markInTransit(String transferNo, String outboundNo)`、`TransferView receive(String transferNo)`
- `wms-transfer-service/src/main/java/com/jim/wms/transfer/service/impl/TransferServiceImpl.java`
  - L66-89 `markInTransit`：匹配 + 状态流转 + outbound_no 回填 + 幂等
  - L93-108 `receive`：IN_TRANSIT→FINISHED，FINISHED 幂等
  - L112-119 `resolve`：先 transfer_no，未命中再 source_no
  - L131-141 `toView`：实体 → TransferView 字段映射
- `wms-transfer-service/src/main/resources/application.yml:38-39` — openfeign 默认超时 `connect-timeout: 3000` / `read-timeout: 10000`（kebab-case）

未改动：wms-common 任何文件、其它服务、V1 迁移、docs/README、topic、端口、库名；`wms.outbox.enabled` 未配置（默认关闭，transfer 不生产 Kafka 事件）。

## 2. V2 迁移全文

```sql
ALTER TABLE wms_transfer_order
    ADD COLUMN outbound_no VARCHAR(64) DEFAULT NULL,
    ADD KEY idx_outbound_no (outbound_no);
```

启动前库内无 `outbound_no` 列，整条 ALTER 由 Flyway 应用；`flyway_schema_history` 记录：version 2 / `transfer outbound link` / success=1。

## 3. 端点实现要点

- 路径与入参严格对齐 `TransferApi`：`POST /internal/transfer/in-transit`（`TransferInTransitCmd{transferNo, outboundNo}`）、`POST /internal/transfer/receive`（`TransferReceiveCmd{transferNo, remark}`），均返回 `Result<TransferView>`。
- 匹配顺序：先按 `transfer_no` 精确匹配；未命中再按 `source_no`（CTM 单号）兜底；仍未命中 → 404 `调拨单不存在: {transferNo}`。
- 状态机：
  - in-transit：仅 `CREATED → IN_TRANSIT`；`IN_TRANSIT`/`FINISHED` 幂等返回当前状态，**不允许回退**（FINISHED 收到 in-transit 保持 FINISHED）。
  - receive：`IN_TRANSIT → FINISHED`；已 `FINISHED` 重复调用幂等返回当前状态；`CREATED` 直接 receive 返回 400（该分支本轮未实测）。
- 幂等写库：`outbound_no` 仅当入参非空且库内为空时补齐；无任何状态/字段变化时不执行 UPDATE、不刷 `update_time`（c、d2、f 三场景 update_time 前后一致可证）。
- 参数校验：controller 仅做空/空白校验，直接 `Result.fail(400, "调拨单号不能为空")`；匹配不到由服务层抛 `BusinessException(404, ...)`，经全局异常处理器转 `Result.fail(404, ...)`。
- 时间来源：所有写入时间字段均为应用 `LocalDateTime.now()`，不依赖 DB `CURRENT_TIMESTAMP`。
- TransferView 填充：transferNo / fromWarehouseCode / toWarehouseCode / skuCode / batchNo / qty / status。

## 4. 真机自验（127.0.0.1:18085，Eureka 关闭，MySQL 在线）

### 启动前快照（snapshot-before.txt）
```
id  transfer_no     source_no        source_system  from_warehouse_code  to_warehouse_code  sku_code  batch_no  qty      status
4   TR-VERIFY-001  NULL             CTM            WH-SH                WH-CD              SKU-V001  BATCH-V1  10.000  CREATED
5   TR-VERIFY-002  CTM-VERIFY-002   CTM            WH-SH                WH-CD              SKU-V002  BATCH-V2  20.000  CREATED
```

### a. in-transit 按 transfer_no 命中（CREATED→IN_TRANSIT，写 outbound_no）
请求体 `req-a-in-transit-001.json`：
```json
{"transferNo":"TR-VERIFY-001","outboundNo":"OUT-VERIFY-001"}
```
响应 `resp-a.json`（HTTP 200）：
```json
{"code":200,"msg":"ok","data":{"transferNo":"TR-VERIFY-001","fromWarehouseCode":"WH-SH","toWarehouseCode":"WH-CD","skuCode":"SKU-V001","batchNo":"BATCH-V1","qty":10.000,"status":"IN_TRANSIT"}}
```
库内：`TR-VERIFY-001 | NULL | OUT-VERIFY-001 | IN_TRANSIT | update_time=2026-11-18 11:12:45`

### b. in-transit 按 source_no 兜底命中（传 CTM 单号）
请求体 `req-b-in-transit-source.json`：
```json
{"transferNo":"CTM-VERIFY-002","outboundNo":"OUT-VERIFY-002"}
```
响应 `resp-b.json`（HTTP 200）：
```json
{"code":200,"msg":"ok","data":{"transferNo":"TR-VERIFY-002","fromWarehouseCode":"WH-SH","toWarehouseCode":"WH-CD","skuCode":"SKU-V002","batchNo":"BATCH-V2","qty":20.000,"status":"IN_TRANSIT"}}
```
库内：`TR-VERIFY-002 | CTM-VERIFY-002 | OUT-VERIFY-002 | IN_TRANSIT`，fallback 生效。

### c. 重复 in-transit 幂等（状态不变、不抛错、update_time 不刷）
请求体 `req-c-in-transit-repeat.json`：
```json
{"transferNo":"TR-VERIFY-001","outboundNo":"OUT-VERIFY-001"}
```
响应 `resp-c.json`（HTTP 200）：
```json
{"code":200,"msg":"ok","data":{"transferNo":"TR-VERIFY-001","fromWarehouseCode":"WH-SH","toWarehouseCode":"WH-CD","skuCode":"SKU-V001","batchNo":"BATCH-V1","qty":10.000,"status":"IN_TRANSIT"}}
```
before_time=11:12:45 → after_time=11:12:45，状态 IN_TRANSIT、outbound_no 保持 OUT-VERIFY-001。

### d. receive：IN_TRANSIT→FINISHED；再次 receive 幂等
请求体 `req-d1-receive-001.json`：
```json
{"transferNo":"TR-VERIFY-001","remark":"verify receive"}
```
响应 `resp-d1.json`（HTTP 200）：
```json
{"code":200,"msg":"ok","data":{"transferNo":"TR-VERIFY-001","fromWarehouseCode":"WH-SH","toWarehouseCode":"WH-CD","skuCode":"SKU-V001","batchNo":"BATCH-V1","qty":10.000,"status":"FINISHED"}}
```
库内：`FINISHED | OUT-VERIFY-001 | update_time=2026-11-18 11:13:15`

请求体 `req-d2-receive-001-again.json`：
```json
{"transferNo":"TR-VERIFY-001","remark":"verify receive again"}
```
响应 `resp-d2.json`（HTTP 200）：
```json
{"code":200,"msg":"ok","data":{"transferNo":"TR-VERIFY-001","fromWarehouseCode":"WH-SH","toWarehouseCode":"WH-CD","skuCode":"SKU-V001","batchNo":"BATCH-V1","qty":10.000,"status":"FINISHED"}}
```
二次 receive 后 update_time 仍为 11:13:15，状态 FINISHED 不变。

### e. 不存在单号 404；空白单号 400
请求体 `req-e1-notfound.json`：
```json
{"transferNo":"NOT-EXIST-999","outboundNo":"OUT-X"}
```
响应 `resp-e1.json`（HTTP 200，body.code=404）：
```json
{"code":404,"msg":"调拨单不存在: NOT-EXIST-999","data":null}
```

请求体 `req-e2-blank.json`（transferNo 为两个空格）：
```json
{"transferNo":"  ","outboundNo":"OUT-X"}
```
响应 `resp-e2.json`（in-transit，HTTP 200，body.code=400）：
```json
{"code":400,"msg":"调拨单号不能为空","data":null}
```
响应 `resp-e3-receive-blank.json`（receive 同校验，body.code=400）：
```json
{"code":400,"msg":"调拨单号不能为空","data":null}
```

### f. FINISHED 单据再调 in-transit → 保持 FINISHED（非法流转被拦截）
请求体 `req-f-in-transit-finished.json`：
```json
{"transferNo":"TR-VERIFY-001","outboundNo":"OUT-VERIFY-001"}
```
响应 `resp-f.json`（HTTP 200）：
```json
{"code":200,"msg":"ok","data":{"transferNo":"TR-VERIFY-001","fromWarehouseCode":"WH-SH","toWarehouseCode":"WH-CD","skuCode":"SKU-V001","batchNo":"BATCH-V1","qty":10.000,"status":"FINISHED"}}
```
库内 before/after 均为 `FINISHED | OUT-VERIFY-001 | 11:13:15`，未回退到 IN_TRANSIT。

### 最终快照（清理前 snapshot-after.txt）
```
id  transfer_no     source_no        outbound_no       status     sku_code  batch_no  qty      create_time          update_time
4   TR-VERIFY-001  NULL             OUT-VERIFY-001    FINISHED   SKU-V001  BATCH-V1  10.000  2026-11-18 11:11:20  11:13:15
5   TR-VERIFY-002  CTM-VERIFY-002   OUT-VERIFY-002    IN_TRANSIT SKU-V002  BATCH-V2  20.000  2026-11-18 11:11:20  11:12:52
```

## 5. 构建结论

- 命令：`mvn -B -DskipTests -pl wms-transfer-service package`（未加 `-am`，未全量 clean；wms-common 已 install）。
- 结果：`BUILD SUCCESS`，编译 7 个源文件，`target/wms-transfer-service-1.0.0.jar` repackage 完成；日志无 ERROR。

## 6. 进程 / 端口 / 数据收尾

- 应用进程 PID 9476（启动前核对命令行为本服务 jar、`--server.port=18085 --eureka.client.enabled=false`），已 Stop-Process；复核进程已消失，18085 处于空闲。
- 未触碰其它 Java 进程（PID 29640，监听 41653/9092/9093，非本轮服务）；8082/8084/8085/8761 均无监听。
- 冒烟数据：`DELETE ... WHERE transfer_no LIKE 'TR-VERIFY-%' OR source_no LIKE 'CTM-VERIFY-%'`，删除 2 行；清理后表内 `COUNT(*)=0`。
- Flyway 历史保留：V1 `init schema`、V2 `transfer outbound link` 均 success=1。

## 7. 未验证 / 未实现项

- outbound 真实 Feign 调用链、Eureka 按名寻址：本轮仅以 `127.0.0.1:18085` 直连验证本服务端点，跨服务调用留给后续验证 agent。
- 收货仅置 `FINISHED`，**未做调入仓库存增加**（库存实物唯一写者为 inventory 服务，不在本轮范围）。
- `CREATED` 单据直接调 `/receive` 返回 400 的分支未构造用例实测；代码路径已保留严格状态校验。
- 控制器未 `implements TransferApi`，类上显式声明 `@RequestMapping/@PostMapping`，与 outbound `InternalOutboundController` 现有风格一致。
- 未发现契约不足；未向 wms-common 申请新增「按出库单号查调拨单」等额外能力（如后续 outbound 需要，再回报扩张契约）。
