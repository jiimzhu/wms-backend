# R4（round4）三个回归缺陷修复 + 真机验证报告

- 项目根：`D:\wms\wms-backend`
- 验证时间：2026-11-18 13:20 – 13:45（Asia/Shanghai）
- 范围：修复 R3 报告第 12 节缺陷 1/2/3 并真机验证；缺陷 4/5 及未覆盖项见末节。
- 中间件全程保持运行：Kafka 4.4.0 KRaft `127.0.0.1:9092`（PID 29640）、MySQL 3306（PID 19848）、Redis 6379（PID 28328）；IDE Java LSP 未终止。
- 证据目录：`D:\wms\wms-backend\.r2-evidence\`（本轮前缀 `R4-`；请求/响应原文在 `R4-payload\`，服务日志在 `R4-logs\`）。

---

## 0. 构建结论

- 分模块编译：`mvn -B -DskipTests -pl wms-inventory-service,wms-outbound-service,wms-integration-service -am compile` → BUILD SUCCESS（`R4-permodule-compile.log`）。
- 全量构建：`mvn -B -DskipTests clean package` → **BUILD SUCCESS，reactor 12/12 SUCCESS**，`Total time: 31.745 s`，日志 `[ERROR]` 计数 **0**（`R4-full-build.log`）。
- jar 形态：全部 fat jar；outbound/integration 无瘦 jar，无需 repackage。

| 模块 | 形态 |
|---|---|
| wms-eureka-server | fat 52.2 MB |
| wms-admin-service | fat 64.3 MB |
| wms-inventory-service | fat 79.4 MB |
| wms-inbound-service | fat 79.4 MB |
| wms-outbound-service | fat 79.4 MB |
| wms-transfer-service | fat 64.3 MB |
| wms-stocktake-service | fat 64.3 MB |
| wms-return-service | fat 64.3 MB |
| wms-report-service | fat 64.3 MB |
| wms-integration-service | fat 64.3 MB |

---

## 1. 缺陷 1：库存空批次维度不匹配 → 同一 SKU 同一库位重复建台账行

### 1.1 根因
`InventoryServiceImpl.lockInventory` 在批次为空白时走 `isNull(batchNo)` 匹配，但新增台账行与流水直接落库入参原值（空串 `""` / 全空格），`batch_no=''` 与 `IS NULL` 不等，导致同一维度被拆成多行。库存侧唯一按批次匹配的位置即 `lockInventory`；锁 key 的 `safe()` 对 null 兜底 `NO_BATCH`，但空串未归一，锁口径也不一致。

### 1.2 改动文件与行号
`wms-inventory-service/src/main/java/com/jim/wms/inventory/service/impl/InventoryServiceImpl.java`
- L44：`changeStock` 入口先 `cmd.setBatchNo(normalizeBatch(cmd.getBatchNo()))`，查询、insert、`writeLog` 明细、`publishEvent` 事件全部复用归一后的 null。
- L104：`freeze` 入口同样归一 `batchNo`（锁 key 与 `lockInventory` 一致口径）。
- L226-228：新增 `normalizeBatch`：null / `""` / 全空格 → null；其余原样保留。
- 未改表、未加唯一键兜底：根因是查询/落库口径不一致，先修代码口径；MySQL InnoDB 唯一索引对 NULL 互不相同，无法靠唯一键强制 NULL 合并。未新增迁移，未改 V1。

### 1.3 真机验证（inventory 8082，默认配置）

**① 同 SKU + 同库位，空串 / 不传 / 全空格 各 +1 共 3 次**

请求（`R4-payload\d1-req1/2/3.json`，SKU-R4-001 / DC01-R4-01）：
- req1：`"batchNo":""`，requestId `R4-D1-REQ-1`，changeQty 1
- req2：无 `batchNo` 字段，requestId `R4-D1-REQ-2`，changeQty 1
- req3：`"batchNo":"   "`，requestId `R4-D1-REQ-3`，changeQty 1

响应（幂等重放 req1 命中现有台账）：
```
{"code":200,"msg":"ok","data":{"warehouseCode":"DC01","locationCode":"DC01-R4-01","skuCode":"SKU-R4-001","batchNo":null,"qty":3.000,...}}
```

SQL 快照（`wms_inventory` / `wms_inventory_log`）：
```
wms_inventory:
| id | sku_code   | batch_no | qty   |
| 30 | SKU-R4-001 | NULL     | 3.000 |   -- 仅一行

wms_inventory_log:
| id  | batch_no | change_qty | before_qty | after_qty | biz_key     |
| 175 | NULL     | 1.000      | 0.000       | 1.000     | R4-D1-REQ-1 |
| 176 | NULL     | 1.000      | 1.000       | 2.000     | R4-D1-REQ-2 |
| 177 | NULL     | 1.000      | 2.000       | 3.000     | R4-D1-REQ-3 |
```
结论：台账 **1 行**、`batch_no=NULL`、qty **3.000**；流水 **3 条**。

**② 真实批次两次 +1 各自独立成行**（SKU-R4-002 / DC01-R4-01）
- `B-R4-B1` +1（REQ-4）、`B-R4-B2` +1（REQ-5）。
```
| id | batch_no | qty   |
| 31 | B-R4-B1  | 1.000 |
| 32 | B-R4-B2  | 1.000 |
```
两行独立，未与 NULL 行合并。

**③ 出库扣减走合并行**：出库单 `OUT1794979768183`（SALE_OUT，SKU-R4-001 / DC01-R4-01 / 空批次，plan 1）。
- pick 200 → confirm 200 → 单据 **FINISHED**。
```
wms_inventory: id30 SKU-R4-001 NULL 2.000   -- 3.000 → 2.000
wms_inventory_log: id180 NULL -1.000 3.000→2.000 biz_key=OUT:OUT1794979768183:SKU-R4-001:DC01-R4-01:NOBATCH
wms_outbound_stock_task: id38 DC01-R4-01 NULL SUCCESS -1.0000
```
扣减正确落在合并后的 NULL 批次行。

---

## 2. 缺陷 2：CTM 补货单明细不带库位 → 按库位拣货命中不到

### 2.1 根因
`OutboundServiceImpl.requireItemDim` 固定按 `locationCode` 等值 `eq` 匹配；CTM 补货明细 `location_code=NULL`，PDA 传入的库位与 NULL 不等 → 404。同时库位入参不支持省略，无法按 SKU（+批次）定位。

### 2.2 改动文件与行号
`wms-outbound-service/src/main/java/com/jim/wms/outbound/service/impl/OutboundServiceImpl.java`
- L112-142 `pick`：匹配改为宽松定位；回写维度时入参给了才覆盖，未给保留明细原维度。
- L739-777 `resolvePickItem`（替代原 `requireItemDim`）：
  - 指定 `locationCode`：按 SKU+库位+批次等值匹配（批次为空按 NULL），沿用原唯一行口径；多行按 404 不存在处理。
  - `locationCode` 为空：按 SKU（+批次，若给了）匹配；**唯一一行**直接拣货；**多行**返回 400，文案提示指定 `locationCode` 与 `batchNo` 并附候选库位/批次。
- L780-802 `resolveLocationFromStock`：明细本身也无库位（CTM 单）时，按仓库+SKU（+批次，过滤 qty>0）查可用库存，唯一库位回填；无库存或跨多库位 → 400 提示指定库位/批次。保证确认扣减落对库位。
- L842-852：`isBlank` / `blankToNull` / `dimText` 辅助方法。
- 接口签名未变：`OutboundService.pick` 与 `OutboundController` 的 `locationCode`/`batchNo` 本就是可空 String；**未改动任何 Feign 契约**（`OutboundApi`/`InventoryApi`/`IntegrationApi`/`AdminApi` 未动）。

### 2.3 真机验证

**① CTM 补货建单（明细无库位）→ 只传 sku+batch 拣货成功 → FINISHED → 扣减正确**

备库：SKU-R4-003 / DC01-R4-02 / B-R4-C1 +2（`R4-payload\d2-seed1.json`，qty 2.000）。

建单：`POST http://127.0.0.1:8089/integration/ctm/replenish/order`
```
请求: {"ctmOrderNo":"CTM-R4-D2-1","orderType":"REPLENISH","sourceWarehouseCode":"DC01",
       "items":[{"skuCode":"SKU-R4-003","batchNo":"B-R4-C1","qty":1}]}
响应: {"code":200,"msg":"ok","data":"OUT1794979805101"}
```
建单后 SQL：item `location_code=NULL`、`batch_no=B-R4-C1`、plan 1.000；单据 CREATED / REPLENISH / CTM。

拣货（只传 sku+batch，不传库位）：`POST /api/outbound/pick`
```
请求: {"outboundNo":"OUT1794979805101","skuCode":"SKU-R4-003","batchNo":"B-R4-C1","qty":1}
响应: {"code":200,"msg":"ok","data":null}
```
明细回填：`location_code=DC01-R4-02`（来自唯一可用库存），picked_qty=1.000。

确认：`POST /api/outbound/confirm` → `{"code":200,"msg":"ok","data":null}`
```
wms_outbound_order: OUT1794979805101 FINISHED, last_error=NULL
wms_inventory: id33 DC01-R4-02 SKU-R4-003 B-R4-C1 1.000   -- 2.000 → 1.000
wms_inventory_log: id184 -1.000 2.000→1.000 biz_key=OUT:OUT1794979805101:SKU-R4-003:DC01-R4-02:B-R4-C1
wms_outbound_stock_task: id39 DC01-R4-02 B-R4-C1 SUCCESS -1.0000
```

**② 多库位订单只传 sku → 400 且文案带候选**

多库位单 `OUT1794979881981`（SKU-R4-004：id45 DC01-R4-03/B-R4-D1；id46 DC01-R4-04/B-R4-D2）。
```
请求: {"outboundNo":"OUT1794979881981","skuCode":"SKU-R4-004","qty":1}
响应: {"code":400,"msg":"该 SKU 存在多个可拣货明细，请指定 locationCode 与 batchNo 后重试；候选: 库位=DC01-R4-03, 批次=B-R4-D1; 库位=DC01-R4-04, 批次=B-R4-D2","data":null}
```

**③ 多库位订单传 locationCode+batchNo → 200（回归不退化）**
```
请求: {"outboundNo":"OUT1794979881981","skuCode":"SKU-R4-004","locationCode":"DC01-R4-03","batchNo":"B-R4-D1","qty":1}
响应: {"code":200,"msg":"ok","data":null}
```
明细：id45 picked 1.000，id46 picked 0.000，命中正确行。

**指定库位口径回归**
- 404 不存在：`locationCode=DC01-R4-99, batchNo=B-R4-D1` → `{"code":404,"msg":"出库明细不存在: OUT1794979881981/SKU-R4-004/DC01-R4-99/B-R4-D1","data":null}`
- 400 超拣：同店再拣 qty 5（已拣 1 / plan 1）→ `{"code":400,"msg":"拣货数量超过计划数量","data":null}`

---

## 3. 缺陷 3：CTM 外部单据未回填 WMS 出库单号

### 3.1 根因
OMS 建单路径在 `createSalesOrder` 成功后调 `ExternalOrderService.markOutboundNo(...)` 回填；CTM 路径 `CtmReplenishController.receiveReplenish` 拿到 `OutboundApi.replenishOrder` 返回的 WMS 单号后直接返回，漏调回填，导致 `wms_external_order.outbound_no=NULL`。

### 3.2 改动文件与行号
`wms-integration-service/src/main/java/com/jim/wms/integration/controller/CtmReplenishController.java`
- L87-90：建单 200 后 `String wmsOutboundNo = resp.getData(); externalOrderService.markOutboundNo(SOURCE_SYSTEM, dto.getCtmOrderNo(), wmsOutboundNo);`（与 OMS 路径 `OmsOutboundController` L72 一致）。
- 取消流程、409/400 文案一律未动；`markOutboundNo` 记录不存在只记日志不抛异常。

### 3.3 真机验证
```
建单响应: {"code":200,"msg":"ok","data":"OUT1794979805101"}
SQL: wms_external_order CTM / CTM-R4-D2-1 / REPLENISH / RECEIVED / outbound_no=OUT1794979805101
```
重复推送同一 `CTM-R4-D2-1`：
```
响应: {"code":409,"msg":"单据已接收，请勿重复推送: CTM-R4-D2-1","data":null}
SQL: external_order 该单号 1 行；wms_outbound_order source_no=CTM-R4-D2-1 仅 1 张，未产生第二张单。
```
CTM 取消空号回归：`POST /integration/ctm/cancel`（无 wmsOutboundNo）→ `{"code":400,"msg":"wmsOutboundNo 不能为空","data":null}`。

---

## 4. 追加回归（两条必测）

### 4.1 健康链路 confirm 返回即 FINISHED（无 CONFIRM_FAILED 中间态）
- `OUT1794979768183`、`OUT1794979805101` confirm 后立即查库均 **FINISHED**，`last_error=NULL`；`wms_event_outbox` 各 **1 条 SENT**：
  - id22 `wms-order-status` key `OUT1794979768183` payload `{"orderNo":"...","message":"出库完成","status":"FINISHED"}`
  - id23 `wms-order-status` key `OUT1794979805101` 同上 FINISHED。
- 未观察到 CONFIRM_FAILED 中间态，无重复发事件。

### 4.2 PICKED 取消仍 409 原文
- 部分出库单 `OUT1794979981259`（plan 5，拣 2，confirm 后 **PICKED**，库存 B-R4-E1 5.000→3.000，任务 id40 SUCCESS）。
- `POST /api/outbound/cancel`：
```
{"code":409,"msg":"已拣货不可直接取消，请走反向入库（退货/调拨退回）回补库存","data":null}
```
取消后单据仍 **PICKED**，库存仍 **3.000**，未回滚、未再扣。

---

## 5. 收尾状态

### 5.1 进程与端口
- 已停本轮自启 5 个 Java：eureka 23448(8761)、admin 18480(8081)、inventory 3784(8082)、outbound 27052(8084)、integration 14036(8089)。
- 停服后 netstat：**8761、8081–8089 全部释放**；`wms-backend` 命令行 Java 进程 0 个。
- 保留运行：Kafka 9092（PID 29640）、MySQL 3306/33060（PID 19848）、Redis 6379（PID 28328）。
- Redis `wms:stock:*` 锁 key：无残留（KEYS 为空）。

### 5.2 清理前后行数快照（`R4-cleanup-before.txt` / `R4-cleanup-after.txt`，脚本 `R4-cleanup.sql`）

| 表 | 清理前 | 清理后 |
|---|---|---|
| inventory.wms_inventory | 8 | **1**（种子 WMS-T/LOC-T/SKU-T-001/B20261118/3.000） |
| inventory.wms_inventory_log | 13 | **1**（种子 id80） |
| inventory.wms_event_outbox | 13 | **1**（种子 id3，SENT） |
| outbound.wms_outbound_order | 4 | 0 |
| outbound.wms_outbound_item | 5 | 0 |
| outbound.wms_outbound_stock_task | 3 | 0 |
| outbound.wms_event_outbox | 3 | 0 |
| integration.wms_external_order | 1 | 0 |
| transfer.wms_transfer_order | 0 | 0 |
| inbound.wms_inbound_order / item / outbox | 0 / 0 / 0 | 0 / 0 / 0 |
| stocktake.wms_stocktake_order | 0 | 0 |
| return.wms_return_order | 0 | 0 |

- 种子核对：库存 id10 `WMS-T/LOC-T/SKU-T-001/B20261118/3.000`；流水 id80（biz_key `IN:IN1794971583777:SKU-T-001:LOC-T:B20261118`）；发件箱 id3（`wms-stock-event`，SENT）。
- Flyway 历史各库保留（admin 2 / inventory 2 / inbound 2 / outbound 3 / transfer 2 / stocktake 1 / return 1 / report 1 / integration 2）；admin 种子 2 仓 / 5 库位 / 2 SKU 未动。
- 冒烟残留复查：`SKU-R4%`、`R4-%` biz_key、`CTM-R4%`、`OUT1794979%` 均 0 行。

### 5.3 边界遵守
- 改动仅落在 wms-inventory-service、wms-outbound-service、wms-integration-service 三处；`wms-common`、outbox 内核、三态认领与租约参数未动。
- 未改 Kafka topic 名；未改既有 Feign 契约签名与路径；未动单体回滚基线 `D:\wms\wms-backend-monolith-backup`；未改 V1 迁移；本轮未新增任何迁移脚本。

---

## 6. 仍未覆盖 / 未解决项

- R3 缺陷 4：调拨 `receive` 只置 FINISHED，不做调入仓（DC02）入库——现状与实体注释一致，本轮未改、未复测。
- R3 缺陷 5：Kafka 不可用的长期 PENDING/FAILED 严格隔离观测——受同库主实例 relay 抢投兜底限制，本轮未复现。
- 跨实例并发认领 / 租约回收 / 重试上限截断（双实例 8084+18084、过期 PROCESSING 回收、max-retry=5）：本轮未跑。
- 真实强杀进程（confirm 途中 kill -9）崩溃窗口时序抓取：本轮未做。
- 盘点（stocktake）、退货（return）、报表（report）、入库（inbound）业务链路：本轮无业务数据，仅随构建通过；未做链路级复测。
- CTM 无库位单在「同 SKU+批次跨多库位有可用库存」时，拣货不传库位会返回 400 要求指定库位（设计如此，避免错扣库位）；该分支本轮以单库位场景验证，多库位歧义分支仅覆盖 400 文案。
