# 阶段二：OMS 销售出库入口 运行验证报告（2026-11-18）

> 工程根 `D:\wms\wms-backend`。本轮只做代码/构建/实测/文档；未改动单体回滚基线、V1 迁移、Kafka topic 名、既有 Feign 签名与路径、Redis 锁。

## 1. 构建与 jar 形态

| 模块 | 命令 | 结论 | 日志 |
| --- | --- | --- | --- |
| wms-common | `mvn -B -DskipTests -pl wms-common install`（阶段一已安装 .m2，阶段二未改 common 源码） | SUCCESS（阶段一） | 阶段一报告 |
| wms-outbound-service | `mvn -B -DskipTests -pl wms-outbound-service package` | BUILD SUCCESS，3.401s，Finished 2026-11-18T12:06:42+08:00 | `logs/p2-build-outbound.log` |
| wms-integration-service | `mvn -B -DskipTests -pl wms-integration-service package` | BUILD SUCCESS，3.223s，Finished 2026-11-18T12:06:56+08:00 | `logs/p2-build-integration.log` |

jar 形态（zip 条目核验，fat jar，可 `java -jar` 直启）：

- `wms-outbound-service-1.0.0.jar` 83,293,511 B，`BOOT-INF/lib` 126 条、`BOOT-INF/classes` 35 条，repackage 已替换主产物（`.original` 留底）。
- `wms-integration-service-1.0.0.jar` 67,439,710 B，`BOOT-INF/lib` 118 条、`BOOT-INF/classes` 31 条。
- 未出现并发 target 锁/文件占用错误，无需 30s 重试。

## 2. 启动与收尾进程

启动顺序 eureka(8761) → admin(8081) → inventory(8082) → outbound(8084) → integration(8089)，默认配置，日志落 `logs/svc/`（各服务 `.out.log` / `.err.log`）。

实测结束后停止本轮自启的 5 个 Java：eureka 35364、admin 14772、inventory 36284、outbound 33548、integration 32336。收尾后残留 Java 仅 Kafka 29640；MySQL（14960/19848）、Redis 28328 保持运行。8761、8081–8089 全部 FREE。IDE Java LSP（PID 8272）未触碰。

## 3. 逐条实测证据

> 全局异常口径：HTTP 状态码 200，业务错误体现在 `body.code`（400/409），非 HTTP 状态码。

### 3.1 首次建单（OMS-SALE-001）—— 通过

- 请求：`POST http://127.0.0.1:8089/integration/oms/outbound/order`，UTF-8 无 BOM 请求体 `logs/req/oms-sale-001.json`。
- 响应（`logs/ev/t1-sale-first.txt`）：`{"code":200,"msg":"ok","data":"OUT1794975435867"}`，HTTP=200。
- SQL 核对：
  - `wms_external_order`：`source_system=OMS`、`external_order_no=OMS-SALE-001`、`business_type=SALE_OUT`、`status=RECEIVED`、`outbound_no=OUT1794975435867` 已回填。
  - `wms_outbound_order`：`order_type=SALE_OUT`、`source_system=OMS`、`source_no=OMS-SALE-001`、`warehouse_code=DC01`。
  - 明细 2 行：SKU-001/DC01-A-01-01/B20261118 plan 2；SKU-002/DC01-A-01-02/NULL plan 1。

### 3.2 重复推送（同 OMS 单号）—— 通过 409

- 响应（`logs/ev/t2-sale-dup.txt`）：`{"code":409,"msg":"单据已接收，请勿重复推送: OMS-SALE-001","data":null}`，HTTP=200。
- SQL：该 OMS 单号 `wms_outbound_order` `COUNT(*)=1`，明细 2 行，外部单据 1 行；未生成第二张出库单。

### 3.3 主数据校验（三条 400，均未登记外部单据）—— 通过

| 场景 | 响应原文（`logs/ev/`） |
| --- | --- |
| 仓库不存在 WH-NOEXIST | `{"code":400,"msg":"仓库不存在: WH-NOEXIST","data":null}`（`t3-oms-bad-wh.txt`） |
| 库位不存在 LOC-NOEXIST | `{"code":400,"msg":"库位不存在: LOC-NOEXIST","data":null}`（`t3-oms-bad-loc.txt`） |
| 商品不存在 SKU-NOEXIST | `{"code":400,"msg":"商品不存在: SKU-NOEXIST","data":null}`（`t3-oms-bad-sku.txt`） |

- SQL：`wms_external_order` 中 `external_order_no LIKE 'OMS-BAD%'` 计数 **0**；校验在登记之前，未把后续正常推送卡成 409。未发现「登记早于校验」缺陷。
- 代码另含库位归属校验：`库位不属于该仓库: {locationCode}`（本轮未构造跨仓库位用例，分支已在 `OmsOutboundController.validateMasterData` 落地）。

### 3.4 全链路拣货 → confirm → 台账/流水 —— 通过

- 拣货（`logs/ev/t4-pick-001.txt`、`t4-pick-002.txt`）：两笔均 `{"code":200,"msg":"ok","data":null}`；按明细行 `locationCode` + `batchNo` 拣货。
- 确认（`logs/ev/t4-confirm-001.txt`）：200。PENDING 任务受 10s 阈值约束，同步认领未命中，由 30s 定时扫描认领扣减；等待后单据 **FINISHED**，两任务 **SUCCESS**。
- 台账（`wms_inventory`，DC01）：
  - SKU-001 / DC01-A-01-01 / B20261118：10.000 → **8.000**（-2）。
  - SKU-002 / DC01-A-01-02 / NULL：10.000 → **9.000**（-1）。
- 流水（`wms_inventory_log`，`business_type=SALE_OUT`，`business_no=OUT1794975435867`）：
  - id127：-2.000，before 10.000 → after 8.000，`biz_key=OUT:OUT1794975435867:SKU-001:DC01-A-01-01:B20261118`。
  - id128：-1.000，before 10.000 → after 9.000，`biz_key=OUT:OUT1794975435867:SKU-002:DC01-A-01-02:NOBATCH`。
- 链路隔离：对照 CTM 回归单 `OUT1794975969332` 无任何扣减流水；OMS 与 CTM 两条链路各扣各的，互不干扰。

### 3.5 单据状态事件（wms-order-status / outbox）—— 通过

- 发件箱（`jim_wms_outbound.wms_event_outbox`）：该单 1 条，`topic=wms-order-status`、`event_key=OUT1794975435867`、`status=SENT`，payload `{"orderNo":"OUT1794975435867","message":"出库完成","status":"FINISHED"}`。
- Kafka 消费（`logs/ev/t5-kafka-order-status.txt`）：`CreateTime:1794975656965  OUT1794975435867  {"orderNo":"OUT1794975435867","message":"出库完成","status":"FINISHED"}`，消息 key=orderNo。
- 口径说明：outbox 在终态（FINISHED）与取消时写事件；PICKING/CONFIRMING 中间态不写。该 FINISHED 单对应 1 条事件，与发件箱记录一致。
- 其余 5 条为更早会话残留消息（OUT1794972363022、OUT1794973273559、IN1794971583777、OUT1794974228602、OUT1794972161208），非本轮产生。

### 3.6 OMS 回调未配置 —— 通过（仅跳过分支，未真实联调）

- 配置 `wms.integration.oms.callback-url: ""`（默认空）。
- 日志（`logs/ev/t6-integration-copy.log`，GBK 解码）：
  `2026-11-18T12:20:56.257+08:00  INFO 32336 --- ... OmsCallbackClient : OMS 回调未配置，跳过, omsOrderNo=OMS-SALE-001, orderNo=OUT1794975435867, status=FINISHED`
- 行为：只记 INFO 跳过，返回成功，单据终态 FINISHED 未被阻断。
- **未为测试配置真实 URL；OMS 回调未真实联调**（已配置后的 HTTP 投递、失败重试、对账未覆盖）。

### 3.7 取消三态

#### 3.7.1 未拣货直接取消 —— 通过

- 建单 `OMS-CANCEL-001` → `OUT1794975793358`（`logs/ev/t7a-cancel-create.txt`，200）。
- 经 OMS 取消端点按 `omsOrderNo` 取消（`logs/ev/t7a-cancel-call.txt`）：`{"code":200,"msg":"ok","data":null}`。
- SQL：单据 **CANCELLED**（SALE_OUT/OMS/OMS-CANCEL-001）。
- 观察项（既有行为，不在本轮范围）：取消路径未回写 `wms_external_order.status`，外部单据行仍为 RECEIVED。

#### 3.7.2 PICKED 后取消 —— 通过 409 原样透传

- 建单 `OMS-PICK-001` → `OUT1794975828057`（SKU-002/DC01-A-01-02 plan 2）；拣 1 件，confirm 后部分出库：`picked_qty=1 < plan_qty=2`，单据 **PICKED**。
- 经 OMS 取消端点（`logs/ev/t7b-cancel-409.txt`）：`{"code":409,"msg":"已拣货不可直接取消，请走反向入库（退货/调拨退回）回补库存","data":null}`，HTTP=200。
- outbound 的 409 code/msg 原样透传到调用方，未归一成 502；取消被拒后单据保持 **PICKED**（picked_qty=1）。

#### 3.7.3 两个单号都不传 —— 通过 400

- 响应（`logs/ev/t7c-cancel-empty.txt`）：`{"code":400,"msg":"omsOrderNo 与 wmsOutboundNo 不能同时为空","data":null}`。

### 3.8 CTM 回归（未退化）—— 通过

| 步骤 | 响应原文（`logs/ev/`） |
| --- | --- |
| 首次推送 CTM-REG-001 | `{"code":200,"msg":"ok","data":"OUT1794975969332"}`（`t8a-ctm-first.txt`） |
| 重复推送 | `{"code":409,"msg":"单据已接收，请勿重复推送: CTM-REG-001","data":null}`（`t8b-ctm-dup.txt`） |
| 取消空 `wmsOutboundNo` | `{"code":400,"msg":"wmsOutboundNo 不能为空","data":null}`（`t8c-ctm-cancel-empty.txt`） |
| 带号取消 | `{"code":200,"msg":"ok","data":null}`（`t8d-ctm-cancel.txt`）；SQL 单据 **CANCELLED**，`order_type=REPLENISH`、`source_system=CTM` |

CTM 两条端点路径、字段、409/400 文案与内部语义未改动。

## 4. 收尾清理与行数快照

清理口径：按本轮单号与 requestId/biz_key 删除各库单据/明细/任务/库存/流水/发件箱/外部单据；保留 Flyway 历史与 admin 种子（WMS-T/LOC-T/SKU-T-001、IN1794971583777）。

| 表（库） | 清理前 | 清理后 |
| --- | ---: | ---: |
| wms_outbound_order（outbound） | 4 | 0 |
| wms_outbound_item（outbound） | 5 | 0 |
| wms_outbound_stock_task（outbound） | 3 | 0 |
| wms_event_outbox（outbound） | 4 | 0 |
| wms_external_order（integration） | 4 | 0 |
| wms_inventory（inventory） | 3 | 1（种子 WMS-T/LOC-T/SKU-T-001 qty 3.000） |
| wms_inventory_log（inventory） | 6 | 1（种子 IN1794971583777 PURCHASE_IN +3.000） |
| wms_event_outbox（inventory） | 6 | 1（种子 WMS-T:SKU-T-001 SENT） |

快照文件：`logs/ev/snapshot-before.txt`、`logs/ev/snapshot-after.txt`；清理脚本 `logs/cleanup.sql`。

Flyway 历史全部 `success=1`：integration V1/V2；outbound V1/V2/V3；inventory V1/V2；admin V1/V2。

残留说明：Kafka topic `wms-order-status` 内历史消息未按消息级删除（本轮清理范围为数据库）；DB 发件箱已清空。

## 5. 文档改动

### 5.1 README.md

- 新增 `6.10 OMS 销售出库入口（integration）—— 已实测（2026-11-18）`：两条路径与请求字段、幂等口径（OMS 单号 + `uk_external_order`，409 文案）、固定 `SALE_OUT`、主数据校验规则与四条 400 文案、落库与按库位扣减、取消三态（含 PICKED 409 文案与原样透传）、事件复用 `wms-order-status`、`callback-url` 默认空与「OMS 回调未真实联调」。
- 第 2 节 integration 职责补 OMS；第 4 节 Feign 表补 `createSalesOrder`、`notifyOmsStatus`、integration→admin 主数据校验；依赖图 AdminApi 消费说明更新；D5 改为「部分接线」；第 7 节未验证项补 OMS 回调未联调；12.1 变更记录补 OMS 入口。

### 5.2 docs/ARCHITECTURE-SPEC.md

- 4.3/4.4/4.5 既有 OMS 契约与代码逐条核对一致，无需更正字段。
- 3.1/3.2 依赖方向补 integration→admin；5.2 配置项补 `wms.integration.oms.callback-url: ""`；10.2 补全主数据四条 400 文案、取消 400/404/400 文案与 PICKED 409 文案。

## 6. 仍未验证项

1. **OMS 回调真实联调**：`callback-url` 保持默认空，仅验证未配置跳过分支；已配置后的 RestTemplate HTTP 投递、连接/读超时、失败 WARN 与重试补偿、对账未实测。
2. **跨仓库位归属校验**：`库位不属于该仓库: {locationCode}` 分支代码已落地，本轮未构造跨仓库位用例。
3. **OMS 取消按 `omsOrderNo` 查无登记（404）/ 未回填出库单号（400）**：分支已实现，本轮未触发。
4. **OMS 链路并发与压测**：未做重复推送并发、同库存维度并发扣减、多实例验证。
5. **Kafka 消费者侧**：本轮仅核对生产消息与 key；下游消费组积压、重试、镜像对账未覆盖。
6. 既有未验证项（CTM 真实回调、Eureka 高可用、transfer/stocktake/return Feign 成功链路等）维持 README 第 7 节口径，本轮未扩展。
