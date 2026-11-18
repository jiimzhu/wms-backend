# 微服务改造端到端集成验证报告

- 验证时间：2026-11-17（Asia/Shanghai）
- 验证环境：Windows + PowerShell 5.1；JDK 17.0.21；Maven 3.9.9；MySQL 8.0.46（127.0.0.1:3306，root/root）；Redis 6379 与 Kafka 9092 未启动；CTM 回调端 9000 不存在。
- 工程目录：`D:\wms\wms-backend`
- 证据目录（本报告所有相对路径均基于此）：
  `D:\wms\wms-backend\.r2-evidence\legacy-2026-11-17-split-round`
- 结论口径：所有数值均以 SQL 直查库为准，不只信接口返回；判断成败以响应体 `body.code` 为准（HTTP 状态码统一为 200）。

## 1. 逐条验证结论表

| # | 验证条目 | 执行命令 / 操作 | 期望 | 实际结果 | 证据路径（根目录下） | 结论 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 全量构建 | `mvn -B -DskipTests clean package`（D:\wms\wms-backend） | 12 个 reactor 条目（父 POM + 11 模块）全部 SUCCESS | 12/12 SUCCESS，BUILD SUCCESS，Total time 37.745 s（墙钟 39.2 s） | `build.log`、`build-elapsed.txt` | 通过 |
| 2 | 独立启动能力 | 解压各 jar 读取 META-INF/MANIFEST.MF，核对 Main-Class / Start-Class | 9 个业务服务 jar 可执行，启动类正确 | 10 个 jar（9 业务 + eureka）Main-Class=JarLauncher，Start-Class 指向对应 `*ServiceApplication` / `EurekaServerApplication` | `jar-manifest-check.txt`、`jar-start-class-check.txt` | 通过 |
| 3 | Eureka 注册 | GET `http://127.0.0.1:8761/eureka/apps`（Accept: application/json） | 可见 admin/inventory/inbound/outbound/report/integration 且 UP | 6 个实例全部 UP：8081/8082/8083/8084/8088/8089 | `http\eureka-apps.json`、`http\eureka-apps-final.json` | 通过 |
| 4 | 服务在线与注册 | 启动 admin/inventory/inbound/outbound/report/integration，轮询端口与启动日志 | 各服务启动完成并在 Eureka UP | 6 端口监听，日志 `Started *Application`，Eureka 状态 UP | `logs\wms-*.out.log`、`http\eureka-apps-final.json` | 通过 |
| 5 | 入库 → 库存 Feign 成功链路（inbound→inventory） | 建单→收货→质检→上架，SQL 核对台账/流水/单据 | 库存真实增加、流水 biz_key=IN:...、单据推进终态 | B-E2E-01 台账 20.000；流水 IN:IN1794892935664:... +20.000；单据 FINISHED | `http\inbound-01-create.json` ~ `inbound-04-putaway.json`；`sql\inbound-before-inventory.txt`、`sql\inbound-after-check.txt` | 通过 |
| 6 | 出库 → 库存 Feign 成功链路（outbound→inventory） | 建单→拣货→确认，SQL 核对台账/任务/流水 | 库存减少、任务 SUCCESS、OUT 流水、单据 FINISHED | B-E2E-01 20.000→12.000；任务 SUCCESS/retry=0；流水 SALE_OUT -8.000；单据 FINISHED | `http\out-success-01-create.json` ~ `out-success-03-confirm.json`；`sql\outbound-success-before.txt`、`sql\outbound-success-after-check.txt` | 通过 |
| 7 | 补偿闭环（失败→补库存→重试成功） | 超库存拣货确认制造 FAILED；入库补库存；等待 `@Scheduled(fixedDelay=60000)` 重试 | CONFIRM_FAILED + FAILED 任务（retry≥1、next_retry_time 有值、last_error 有内容）；补货后任务 SUCCESS、单据按规则推进；仅一套扣减流水 | 阶段一 FAILED/retry=1/next_retry=13:25:47/last_error 有内容；阶段二补货 10.000；阶段三 13:26:36 SUCCESS、单据 FINISHED；仅 1 条 IN + 1 条 OUT，无重复扣减 | `http\comp-01-create.json`、`comp-02-pick.json`、`comp-03-confirm-fail.json`、`comp-restock-01~04`；`sql\comp-before-sku002.txt`、`comp-phase1-failed.txt`、`comp-phase2-restocked.txt`、`comp-phase3-success.txt` | 通过 |
| 8 | CTM 对外契约（integration） | 首次推送→重复推送→空号取消→带号取消；SQL 核对外部单据/出库单/补偿任务 | 首次返回单号；重复 409 且文案逐字一致；空号 400；取消成功且单据/任务 CANCELLED 不复活 | 首次 200/单号 OUT1794893255478；重复 code=409 `单据已接收，请勿重复推送: CTM-E2E-001`；空号 code=400 `wmsOutboundNo 不能为空`；A/B 取消后 CANCELLED，B 任务 CANCELLED，跨扫描周期未复活、无 B-E2E-03 流水 | `http\ctm-01-replenish-first.json`、`ctm-02-replenish-duplicate.json`、`ctm-03-cancel-blank.json`、`ctm-04-replenish-B.json`、`ctm-05-cancel-B.json`、`ctm-06-cancel-A.json`；`sql\ctm-external-order-after.txt`、`ctm-B-before-cancel.txt`、`ctm-after-cancel.txt`、`ctm-B-after-scan.txt` | 通过 |
| 9 | 报表 Feign 聚合（report→inventory） | GET `/api/report/stock-summary?warehouseCode=DC01`，SQL 直算 SUM 核对 | 聚合数值与 inventory on-hand 口径一致 | SKU-001 onHand 27.000/freeze 0/available 27.000；SKU-002 0.000/0/0；与 SQL SUM 一致 | `http\report-stock-summary-dc01.json`、`report-stock-summary-all.json`；`sql\report-direct-dc01.txt` | 通过 |
| 10 | 静态合规核查 | yml 解析、源码 grep、迁移脚本/库表比对、旧库连接串扫描、Feign 归属扫描 | yml 一致；越权引用为零；迁移不跨库；无旧库直连；Feign 只在 common.api | yml 9/9 通过；越权引用为零（1 处 Javadoc 注释除外）；表归属一致；无 `jim_wms` 直连串；4 个 `@FeignClient` 全在 wms-common/api | `sql\static-yml-check.txt`、`static-source-grep.txt`、`static-imports-olddb.txt`、`static-migration-tables.txt`、`static-db-tables.txt`、`static-direct-and-errors.txt` | 通过（含 2 条观察项，见第 4 节） |
| 11 | 收尾：停进程、释端口、清冒烟数据 | 停止本阶段启动的 java 进程；核对端口；DELETE 业务表；保留 Flyway 历史与 admin 种子 | 8761/8081-8089 释放；业务表清零；种子与历史表保留 | 全部目标端口 free；业务表 0 行；admin 种子 warehouse=2/area=4/location=5/sku=2；各库 flyway_schema_history 保留 | `sql\pre-clean-dump.txt`、`sql\post-clean-rowcount.txt`；`logs\*.pid` | 通过 |

## 1A. 返工与最终回归（第二轮，2026-11-17）

> 本节为两处源码返工后的独立回归：结论全部来自本轮真实重跑，未引用第一轮旧日志。证据根目录同上，回归子目录 `regression\`（`regression\build.log`、`regression\http\`、`regression\sql\`、`regression\logs\`）。

### 1A.1 返工改动核对（源码）

- `wms-integration-service\...\controller\CtmReplenishController.java:60`：`.orderType(dto.getOrderType())` 原样透传外部值，不再在 integration 侧把 `TRANSFER` 预映射成 `TRANSFER_OUT`；`:66-67` 发 Feign 前打 INFO 记录原始 orderType。
- `wms-outbound-service\...\impl\OutboundServiceImpl.java:194-197`：内部建单类型幂等映射，`TRANSFER` 或 `TRANSFER_OUT`（equalsIgnoreCase）→ `TRANSFER_OUT`，其余（含 REPLENISH、空值）→ `REPLENISH`；`:259` 流水类型按 `TRANSFER_OUT` 判定；`:305-315` `markSuccess` 改用 `Wrappers.lambdaUpdate()` 显式 `.set(OutboundStockTask::getLastError, null)`。

### 1A.2 全量构建 —— 通过

- 命令：`cd D:\wms\wms-backend; mvn -B -DskipTests clean package`
- 实际：12 个 reactor 条目（父 POM + 11 模块）全部 SUCCESS，BUILD SUCCESS，**Total time 36.314 s**，Finished `2026-11-17T14:04:33+08:00`。
- 证据：`regression\build.log`（完整日志与 Reactor Summary）、`regression\build-elapsed.txt`。

### 1A.3 Eureka 注册 —— 通过

- 启动：eureka(8761)、admin(8081)、inventory(8082)、outbound(8084)、integration(8089)。
- `GET http://127.0.0.1:8761/eureka/apps`（Accept: application/json）原始 JSON：`regression\http\eureka-apps.json`。

| 应用名 | 实例端口 | 状态 |
| --- | --- | --- |
| WMS-ADMIN-SERVICE | 8081 | UP |
| WMS-INVENTORY-SERVICE | 8082 | UP |
| WMS-OUTBOUND-SERVICE | 8084 | UP |
| WMS-INTEGRATION-SERVICE | 8089 | UP |

- 启动日志：`regression\logs\wms-*.out.log`（均 `Started *ServiceApplication`）；PID：`regression\logs\*.pid`。

### 1A.4 CTM TRANSFER 调拨链路 E2E —— 通过（返工核心验证）

链路：inventory 内部补货 → CTM 推送 `orderType=TRANSFER` → 拣货 → 确认 → SQL 核对出库单类型、台账、流水类型、补偿任务。

**步骤 0 基线**（`regression\sql\00-baseline.sql`）：`wms_inventory`、`wms_inventory_log`、`wms_outbound_order`、`wms_external_order` 均 0 行。

**步骤 1 补货**：`POST http://127.0.0.1:8082/internal/inventory/change-stock`
请求体（`regression\http\01-restock.req.json`）：
```json
{"warehouseCode":"DC01","locationCode":"DC01-A-01-01","skuCode":"SKU-001","batchNo":"B-REG-01","changeQty":10.000,"businessType":"PURCHASE_IN","businessNo":"REG-RESTOCK-001","remark":"regression restock","requestId":"REG-RESTOCK-001"}
```
响应（`01-restock.resp.txt`）：`{"code":200,"msg":"ok","data":{"...qty":10.000,"freezeQty":0,"stockStatus":1}}`。

SQL 原文（`01-after-restock.sql`）与结果（`01-after-restock.txt`）：
```sql
SELECT warehouse_code,location_code,sku_code,batch_no,qty,freeze_qty FROM jim_wms_inventory.wms_inventory;
SELECT id,sku_code,business_type,business_no,change_qty,before_qty,after_qty,biz_key FROM jim_wms_inventory.wms_inventory_log ORDER BY id;
```
```
warehouse_code  location_code    sku_code  batch_no   qty     freeze_qty
DC01            DC01-A-01-01      SKU-001   B-REG-01   10.000  0.000
id  sku_code  business_type  business_no       change_qty  before_qty  after_qty  biz_key
7   SKU-001   PURCHASE_IN    REG-RESTOCK-001   10.000      0.000       10.000     REG-RESTOCK-001
```

**步骤 2 推送调拨单**：`POST http://127.0.0.1:8089/integration/ctm/replenish/order`
请求体（`02-ctm-transfer.req.json`）：
```json
{"ctmOrderNo":"CTM-REG-001","orderType":"TRANSFER","sourceWarehouseCode":"DC01","targetWarehouseCode":"DC02","priority":"1","items":[{"skuCode":"SKU-001","batchNo":"B-REG-01","qty":3}]}
```
响应（`02-ctm-transfer.resp.txt`）：`{"code":200,"msg":"ok","data":"OUT1794895803002"}`。

SQL 原文（`02-after-ctm.sql`）与结果（`02-after-ctm.txt`）：
```sql
SELECT outbound_no,order_type,source_system,source_no,warehouse_code,target_warehouse_code,status FROM jim_wms_outbound.wms_outbound_order WHERE outbound_no='OUT1794895803002';
SELECT source_system,external_order_no,business_type,status FROM jim_wms_integration.wms_external_order WHERE external_order_no='CTM-REG-001';
```
```
outbound_no       order_type    source_system  source_no   warehouse_code  target_warehouse_code  status
OUT1794895803002  TRANSFER_OUT  CTM            CTM-REG-001 DC01            DC02                    CREATED
source_system  external_order_no  business_type  status
CTM            CTM-REG-001        TRANSFER       RECEIVED
```
> 关键判定：出库单 `order_type=TRANSFER_OUT`（不是 REPLENISH）；外部单 `business_type=TRANSFER` 原样保留。

**步骤 3 拣货**：`POST http://127.0.0.1:8084/api/outbound/pick`（`03-pick.req.json`：`outboundNo=OUT1794895803002, skuCode=SKU-001, locationCode=DC01-A-01-01, qty=3`）→ `{"code":200,"msg":"ok","data":null}`。

**步骤 4 确认**：`POST http://127.0.0.1:8084/api/outbound/confirm`（`04-confirm.req.json`：`outboundNo=OUT1794895803002`）→ `{"code":200,"msg":"ok","data":null}`。

SQL 原文（`03-after-confirm.sql`）与结果（`03-after-confirm.txt`）：
```sql
SELECT warehouse_code,location_code,sku_code,batch_no,qty,freeze_qty FROM jim_wms_inventory.wms_inventory WHERE sku_code='SKU-001';
SELECT id,sku_code,business_type,business_no,change_qty,before_qty,after_qty,biz_key FROM jim_wms_inventory.wms_inventory_log ORDER BY id;
SELECT outbound_no,order_type,status FROM jim_wms_outbound.wms_outbound_order WHERE outbound_no='OUT1794895803002';
SELECT outbound_no,sku_code,status,change_qty,retry_count,next_retry_time,(last_error IS NULL) AS last_error_is_null,last_error FROM jim_wms_outbound.wms_outbound_stock_task WHERE outbound_no='OUT1794895803002';
```
```
inventory  DC01  DC01-A-01-01  SKU-001  B-REG-01  7.000  0.000
log  7  SKU-001  PURCHASE_IN   REG-RESTOCK-001    +10.000  0.000  10.000  REG-RESTOCK-001
log  8  SKU-001  TRANSFER_OUT  OUT1794895803002  -3.000   10.000  7.000   OUT:OUT1794895803002:SKU-001:DC01-A-01-01:B-REG-01
order  OUT1794895803002  TRANSFER_OUT  FINISHED
task  OUT1794895803002  SKU-001  SUCCESS  -3.0000  retry_count=0  next_retry_time=NULL  last_error_is_null=1  last_error=NULL
```

- 库存：**10.000 → 7.000**（-3.000，精确到小数位）。
- 流水：新行 `business_type=TRANSFER_OUT`（不是 SALE_OUT），before 10.000 → after 7.000。
- 补偿任务：**SUCCESS**，retry_count=0，**`last_error` 为 NULL**（D3 修复复测通过）。
- 单据：`FINISHED`。

### 1A.5 CTM 契约复测 —— 通过

| 用例 | 请求 | 响应体（原文） | 库表核对 | 证据 |
| --- | --- | --- | --- | --- |
| 同 `ctmOrderNo` 二次推送 CTM-REG-001 | POST `/integration/ctm/replenish/order` | HTTP 200，`{"code":409,"msg":"单据已接收，请勿重复推送: CTM-REG-001","data":null}` | 未生成第二张出库单 | `http\05-repeat.req.json` / `05-repeat.resp.txt` |
| 空 `wmsOutboundNo` 取消 | POST `/integration/ctm/cancel` | `{"code":400,"msg":"wmsOutboundNo 不能为空","data":null}` | — | `http\06-cancel-empty.req.json` / `06-cancel-empty.resp.txt` |
| 带号取消 OUT1794895933020 / CTM-REG-002 | POST `/integration/ctm/cancel` | `{"code":200,"msg":"ok","data":null}` | 出库单 CANCELLED；补偿任务 CANCELLED | `http\10-cancel-order2.req.json` / `10-cancel-order2.resp.txt` |

取消前 FAILED 阶段快照（`sql\04-order2-failed.txt`）：第二单 `OUT1794895933020`（REPLENISH，SKU-002 拣 5，库存 0）确认后单据 **CONFIRM_FAILED**，任务 **FAILED**，retry_count=1，next_retry_time=`2026-11-17 14:13:35`，last_error=`code=400, msg=库存不足, skuCode=SKU-002, 现有=0, 需要=5.0000`；无 SKU-002 扣减流水。

取消后快照（`sql\05-order2-cancelled.txt`）：单据 **CANCELLED**，任务 **CANCELLED**（retry_count 仍 1）。跨一个补偿扫描周期（约 75 s）复核（`sql\06-order2-after-scan.txt`）：单据与任务仍 **CANCELLED**，未被重试复活；`SELECT COUNT(*) ... WHERE sku_code='SKU-002'` = **0**，无重复/残留扣减流水。

### 1A.6 inventory 对外端点清单 —— 与基线一致

源码核对 + 运行探测（`http\11-inventory-endpoints.md`、`12-inv-query.resp.txt`、`13-inv-onhand.resp.txt`）：

- 对外 `/api/inventory`：`GET /query`、`GET /on-hand`、`POST /freeze`（对齐基线端点 6/7/8）。
- 内部 `/internal/inventory`：`POST /change-stock`、`POST /freeze`、`GET /query`、`GET /total-on-hand`、`GET /list`。
- 运行探测：`/query?warehouseCode=DC01&skuCode=SKU-001` 返回 qty 7.000；`/on-hand` 返回 7.000，口径一致。
- 基线文档 `stock-history` / `stockHistory` 全文 **0 命中**；无 `/api/inventory/stock-history` 端点。

### 1A.7 静态合规复扫 —— 通过

证据：`sql\static-yml-regression.txt`、`sql\static-regression-check.txt`、`sql\static-olddb-precise.txt`。

- 10 个 `application.yml`：`application.name` 与模块名一致；端口 8761/8081-8089 为分配值且不重复；datasource 指向各自拆分库；eureka `defaultZone` 指向 8761。
- 除 inventory 外，源码无 `InventoryMapper`、无 `com.jim.wms.inventory` import、无 `wms_inventory` 表名引用；唯一命中为 `InventoryView.java:10` 的 Javadoc 注释（D6，非代码引用）。
- 无裸旧单体库 `jim_wms` 直连串；9 个数据源均指向 `jim_wms_<服务>` 拆分库。
- 4 个 `@FeignClient` 全部位于 `wms-common\...\api`（AdminApi / InventoryApi / OutboundApi / IntegrationApi），业务服务只注入该包。
- 内部控制器（admin `/internal/admin`、inventory `/internal/inventory`、outbound `/internal/outbound`）类级 `@RequestMapping` 与方法级 `@PostMapping`/`@GetMapping` 均显式标注，不依赖 Feign 接口注解继承。

### 1A.8 收尾状态（第二轮）

- 本轮启动的 5 个 WMS java 进程已全部停止；8761、8081-8089 端口均 free；保留与 WMS 无关的 IDE Java 语言服务器（PID 8272，JDK 26）。
- 冒烟业务数据已清理；admin 种子保留（warehouse=2、area=4、location=5、sku=2）；各库 `flyway_schema_history` 保留；旧单体库 `jim_wms` 未被直连、未改动。
- 证据：`sql\07-pre-clean-rows.txt`、`sql\09-post-clean-rows.txt`、`sql\10-flyway-history.txt`、`sql\08-cleanup.sql`。

## 1B. Redis 分布式锁改造与验证（第三轮，2026-11-17）

> 本节为锁改造后的独立真实环境验证：本轮不改业务代码、不改 wms-common、不改 yml、不改 Flyway 脚本、不改 Feign 契约与 CTM 接口；仅允许改两份文档。证据根目录为本轮独立证据目录：
> `D:\wms\wms-backend\.r2-evidence\legacy-2026-11-17-lock-round`
>
> 环境事实：Redis `127.0.0.1:6379` v5.0.14.1（Windows），`maxmemory-policy=noeviction`、`maxmemory=0`、`appendonly=no`、验证前 DBSIZE=0、Lua EVAL 可用；MySQL `127.0.0.1:3306` root/root 在跑；**Kafka 9092 仍不可连（保持未验证口径）**；CTM 回调端 9000 不存在。判断成败以响应体 `body.code` 为准（HTTP 状态码统一 200）；最终台账与流水一律 SQL 直查核对，不只看接口返回。

### 1B.1 改造前后差异（旧降级 vs 新实现）

| 维度 | 改造前（第一轮基线） | 改造后（本轮实测） |
| --- | --- | --- |
| Redis 不可用行为 | `RedisLockUtil` 降级为进程内锁，主流程不中断 | 默认抛 **503** 快速失败，不静默降级、不静默放行；仅 `wms.lock.allow-local-fallback=true` 显式打开才回退进程内锁（默认 false） |
| 加锁 | 旧实现未验证分布式互斥 | `SET key token PX lease NX`；token = `实例标识(spring.application.name:server.port):线程ID:UUID` |
| 释放 | 旧实现未验证 token 比对 | Lua 脚本 `if redis.call('get',KEYS[1])==ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end`，比对 token 后再 del，防止误删他人锁 |
| 等待策略 | 未验证 | 有界等待：`wait-timeout` 内按 `retry-interval` 重试，超时抛 **429** |
| 异常处理 | — | Redis 连接失败/超时抛 **503**；等待被中断抛 503；不无限等待 |
| 释放时机 | — | 有活动事务时 `afterCompletion`（提交/回滚后）释放；无事务时 `finally` 释放；`AtomicBoolean` 保证幂等只释放一次；释放失败只记 ERROR，不覆盖原始业务异常 |
| 启动自检 | — | `@PostConstruct` PING 一次；成功记 INFO「Redis 锁通道就绪…PING=PONG」，失败记 ERROR，不阻断启动 |

`wms.lock` 配置项与默认值（`LockProperties.java`，前缀 `wms.lock`）：

| 配置项 | 默认值 | 含义 |
| --- | --- | --- |
| `wms.lock.wait-timeout` | `2000ms` | 获取锁有界等待时长，超时抛 429 |
| `wms.lock.lease-time` | `30s` | 锁租约（key 的 PX 过期时间） |
| `wms.lock.retry-interval` | `50ms` | 未抢到锁重试间隔 |
| `wms.lock.allow-local-fallback` | `false` | Redis 不可用时是否回退进程内锁兜底 |

代码位置：`wms-common\src\main\java\com\jim\wms\common\lock\RedisLockUtil.java`、`LockProperties.java`；接入点 `wms-inventory-service\...\InventoryServiceImpl.java` 的 `changeStock`（41-93）与 `freeze`（100-125）；锁 key 规则 `wms:stock:{warehouse}:{location}:{sku}:{batch或NO_BATCH}`。

### 1B.2 全量构建 —— 通过

- 命令：`cd D:\wms\wms-backend; mvn -B -DskipTests clean package`
- 实际：12 个 reactor 条目（父 POM + 11 模块）全部 SUCCESS，BUILD SUCCESS；Maven Total time **37.887 s**，墙钟 **39.355 s**（`ELAPSED_MS=39355`），Finished `2026-11-17T14:53:38+08:00`。
- 证据：`build.log`、`build-elapsed.txt`。

### 1B.3 启动与 Eureka 注册 —— 通过

- 启动：`wms-eureka-server`（8761）；`java -jar wms-inventory-service\target\wms-inventory-service-1.0.0.jar`（8082）。
- 8082 启动日志关键行（`logs\wms-inventory-service.out.log`）：
  - `Redis 锁通道就绪（127.0.0.1:6379:0），PING=PONG`
  - `Started InventoryServiceApplication in 8.325 seconds`
- 日志中**没有**「降级为进程内锁」/「回退进程内锁」WARN。
- `GET http://127.0.0.1:8761/eureka/apps`（Accept: application/json）：

| 应用名 | 端口 | 状态 | host | instanceId |
| --- | --- | --- | --- | --- |
| WMS-INVENTORY-SERVICE | 8082 | UP | LAPTOP-KTLR75G6 | wms-inventory-service:8082 |

- 证据：`http\eureka-apps.json`、`http\eureka-apps-summary.txt`、`logs\wms-eureka-server.out.log`、`logs\wms-inventory-service.out.log`、`logs\*.pid`。

### 1B.4 单请求走 Redis 分支 —— 通过

- 预热请求 `POST http://127.0.0.1:8082/internal/inventory/change-stock`（requestId `LOCK-WARM-01`）。
- 日志抓到加锁/释放两行原文（`http\warmup-lock-lines.txt`）：
  - `Redis 加锁成功 key=wms:stock:DC01:DC01-A-01-01:SKU-LOCK-01:B-LOCK-01 token=wms-inventory-service:8082:42:fade4dda-cb52-43f0-8899-b4072bff04ba`
  - `Redis 释放锁成功 key=wms:stock:DC01:DC01-A-01-01:SKU-LOCK-01:B-LOCK-01 token=wms-inventory-service:8082:42:fade4dda-cb52-43f0-8899-b4072bff04ba`
- token 形态符合 `wms-inventory-service:8082:线程ID:UUID`（appName:port:线程:UUID），加锁与释放 token 一致。
- 请求后 `EXISTS=0`、`TTL=-2`、`KEYS wms:stock:*` 空、`DBSIZE=0`。
- 证据：`http\warmup.req.json`、`http\warmup.resp.txt`、`http\warmup.resp.meta.txt`、`http\warmup-lock-lines.txt`。

### 1B.5 并发争抢（核心）—— 通过

**测试维度**：warehouseCode=`DC01`，locationCode=`DC01-A-01-01`，skuCode=`SKU-LOCK-01`，batchNo=`B-LOCK-01`；锁 key=`wms:stock:DC01:DC01-A-01-01:SKU-LOCK-01:B-LOCK-01`。

**请求方式（真并发）**：PowerShell 5.1 主线程内使用 .NET `System.Net.Http.HttpClient` 同时 `PostAsync` 收集 `Task[]` 后 `WaitAll` 屏障等待，非串行 for 循环伪装并发；脚本 `concurrency-runner.ps1`。
> 口径说明：结果 JSON 中每条 `elapsedMs` 是 `WaitAll` 屏障后观测到的上界，不是逐请求真实完成耗时；`globalElapsedMs` 为整批墙钟。逐请求 requestId、changeQty、businessType、HTTP/body 码、msg、elapsedMs 见对应结果 JSON。

**测试实例启动参数（仅 CLI 覆盖，未改 yml/代码）**：`--spring.kafka.producer.properties.max.block.ms=50`（规避 Kafka 不可用时生产者在临界区内同步阻塞元数据获取拉长持锁，见 1B.10 观察项 O1）；场景 B 复测另 CLI 覆盖 `--wms.lock.wait-timeout=10000ms`。

#### 场景 A：20 并发各 +1

- 请求：20 个并发 `PURCHASE_IN`，changeQty=+1，requestId `CONC-A-01` … `CONC-A-20`，各不相同。
- 响应分布：**20/20 body.code=200**（HTTP 200，msg=ok）；整批 `globalElapsedMs=2122`。
- SQL 直查（`sql\conc-a-after.txt`）：台账基线 **0.000** → 终值 **20.000**；`wms_inventory_log` 恰好新增 **20** 行；流水 before→after 链式 `0.000→1.000→…→20.000`，无丢失更新。
- 证据：`http\conc-a-results.json`、`sql\conc-a-baseline.txt`、`sql\conc-a-after.txt`。

#### 场景 B：20 并发，10 个 +1 / 10 个 -1 混合

- 请求：10 个 `PURCHASE_IN` +1（`CONC-B-P01` … `CONC-B-P10`）与 10 个 `SALE_OUT` -1（`CONC-B-M01` … `CONC-B-M10`），同维度并发。
- 响应分布：**20/20 body.code=200**（HTTP 200，msg=ok）；整批 `globalElapsedMs=2198`。
- SQL 直查（`sql\conc-b-after.txt`）：台账基线 **20.000** → 终值 **20.000**（净变化 0）；本轮 B 流水 **20** 行，累计总流水 **40** 行；期间 after_qty 最小 **19.000**、最大 **24.000**，未出现负库存，无失败、无丢失更新。
- 证据：`http\conc-b-results.json`、`sql\conc-b-baseline.txt`、`sql\conc-b-after.txt`。

### 1B.6 异常路径（库存不足）—— 通过

- 前置台账 20.000。请求 `NEG-01`：changeQty=`-120`（现有 20，需扣 120）。
- 响应（`http\neg-01.resp.txt`）：HTTP 200，`body.code=400`，msg=`库存不足, skuCode=SKU-LOCK-01, 现有=20.000, 需要=120`；耗时 **99 ms**（`http\neg-01.resp.meta.txt`）。
- 失败请求日志同样有「Redis 释放锁成功 key=… token=…」：加锁 23.825 → 释放 23.834，之后才 WARN 库存不足（失败路径也正常释放锁）。
- 紧接正常 +1 请求 `OK-AFTER-NEG`：body.code=**200**，qty=21.000，耗时 **164 ms**，未被卡住、未返回 429（`http\ok-after-neg.resp.txt` / `.meta.txt`）。
- 锁清理：`EXISTS=0`、`TTL=-2`、`KEYS wms:stock:*` 空、`DBSIZE=0`。
- 证据：`http\neg-01.req.json`、`neg-01.resp.txt`、`neg-01.resp.meta.txt`；`http\ok-after-neg.req.json`、`ok-after-neg.resp.txt`、`ok-after-neg.resp.meta.txt`；`redis\429-after-del.txt`、`redis\final-redis-state.txt`。

### 1B.7 有界等待（429）—— 通过

- redis-cli 手工占锁：`SET wms:stock:DC01:DC01-A-01-01:SKU-LOCK-01:B-LOCK-01 held-by-cli PX 15000` → OK；请求前 `TTL=15`、`GET=held-by-cli`、`EXISTS=1`。
- 同维度请求 `WAIT-429-02`（+1）：HTTP 200，`body.code=429`，msg=`该库存维度正在被其他任务变更，请稍后重试: wms:stock:DC01:DC01-A-01-01:SKU-LOCK-01:B-LOCK-01`；耗时 **2103 ms**（≈ `wait-timeout=2000ms`）。
- 请求后 `TTL=13`：CLI 持有的锁未被应用误删（token 比对保护生效）。
- 事后 `DEL`（返回 0，key 已自然过期）、`EXISTS=0`、`KEYS wms:stock:*` 空、`DBSIZE=0`。
- 备注（首次尝试归档）：`WAIT-429-01` 首轮用 `PX 5000`，5s 锁 TTL 在请求发出前已过期，请求正常拿到锁（误成功，台账 +1，落流水 id=76）；重做改用 `PX 15000` 复现 429。
- 证据：`redis\429-hold-lock.txt`、`redis\429-hold-lock-retry.txt`、`redis\429-after-del.txt`；`http\wait429.req.json` / `wait429.resp.txt` / `.meta.txt`、`http\wait429b.req.json` / `wait429b.resp.txt`。

### 1B.8 Redis 不可用快速失败（503）与兜底开关 —— 通过

**503 快速失败（兜底默认关闭）**：

- 临时实例：`java -jar wms-inventory-service-1.0.0.jar --server.port=18082 --spring.data.redis.port=6390`（6390 无服务），`allow-local-fallback` 取默认 false。
- 启动自检：ERROR「Redis 不可用，加锁操作将快速失败(503)，原因=Unable to connect to Redis」，不阻断启动；无本地兜底记录。
- 请求 `TEST-503-01`：HTTP 200，`body.code=503`，msg=`Redis 锁服务不可用，已拒绝本次库存变更: Unable to connect to Redis`；耗时 **253 ms**（快速失败，非无限等待）。
- 该实例日志本地兜底（回退进程内锁）匹配数=**0**；`TEST-503-01` 未落任何流水（`COUNT(*)=0`）。
- 证据：`http\test503.req.json`、`test503.resp.txt`、`test503.resp.meta.txt`、`test503-logline.txt`；`logs\inventory-18082-false.out.log`。

**兜底开关复测（`allow-local-fallback=true`）**：

- 停掉 18082，加 `--wms.lock.allow-local-fallback=true` 重启，仍指向 6390。
- 启动自检：ERROR Redis 不可用，紧接 WARN「wms.lock.allow-local-fallback=true：进程内兜底锁仅限单机开发，生产环境禁用」。
- 请求 `TEST-FALLBACK-01`：HTTP 200，`body.code=200`，qty=23.000，耗时 **1713 ms**（首请求含生产者/DB 预热）；日志 WARN「Redis 不可用，按 wms.lock.allow-local-fallback=true 回退进程内锁 key=wms:stock:DC01:DC01-A-01-01:SKU-LOCK-01:B-LOCK-01（仅限单机开发，生产禁用）」。
- 结论：开关显式生效；默认 false 时 Redis 不可用走 503，置 true 才走进程内兜底。该临时实例用完已立即停止，18082 listen=0。
- 证据：`http\testfallback.req.json`、`testfallback.resp.txt`、`testfallback.resp.meta.txt`、`testfallback-logline.txt`；`logs\wms-inventory-18082.out.log` / `.err.log` / `.pid`。

### 1B.9 redis-cli 锁 key 清理证据

所有业务请求完成后执行（`redis\final-redis-state.txt`）：

```
KEYS 'wms:stock:*' => []
EXISTS wms:stock:DC01:DC01-A-01-01:SKU-LOCK-01:B-LOCK-01 => 0
DBSIZE => 0
INFO memory maxmemory-policy => maxmemory_policy:noeviction
```

加锁瞬间证据：应用日志 token 原文见 `http\warmup-lock-lines.txt`；手工 `SET ... PX` 后 `TTL`/`GET` 输出见 `redis\429-hold-lock-retry.txt`（TTL 15 → 13，GET=held-by-cli）。

### 1B.10 观察项（仅记录，未改代码）

- **O1：Kafka 不可用拉长临界区持锁，轻度并发即可触发 429。** 9092 不可达时，Kafka 生产者在持锁临界区内同步阻塞获取元数据，默认 `max.block.ms=1000` 使单笔持锁约 1 s。首轮默认配置 20 并发下：日志「正在被其他任务变更」WARN=**17**、「Redis 加锁成功」=**5**；场景 B 首轮 17×200 / 3×429（`CONC-B-M02`、`CONC-B-M08`、`CONC-B-M09`）。定位后测试实例 CLI 覆盖 `max.block.ms=50`，稳态临界区持锁约 83 ms，复测 A/B 全部 200。建议方向：事件在锁释放后异步发布，避免在临界区内同步等待生产者元数据。
- 归档证据：`logs\inventory-run1-default-kafka.out.log` / `.err.log`、`http\conc-b-results-run1-tight.json`；复测通过证据见 1B.5。

### 1B.11 收尾状态（第三轮）

- 本轮启动的 WMS java 进程已全部停止：eureka 8761、inventory 8082；临时实例 18082 已停。8761 / 8082 / 18082 端口 listen=**0**；保留与 WMS 无关的 IDE Java 语言服务器（PID 8272，JDK 26）。
- 冒烟业务数据（SKU-LOCK-01 / B-LOCK-01）已清理：`jim_wms_inventory.wms_inventory=0`、`wms_inventory_log=0`；admin 种子保留（warehouse=2、area=4、location=5、sku=2）；各库 `flyway_schema_history` 保留。
- 旧单体库 `jim_wms` 中 `wms_inbound_order=3`、`wms_inbound_item=3` 为前轮遗留数据，本轮未触达、未删除；其余业务表 0 行。
- 证据：`sql\pre-clean-smoke-dump.txt`、`sql\post-clean-counts.txt`、`sql\post-clean-table-counts.txt`、`logs\ports-after-stop.txt`、`logs\java-processes-before-stop.txt`。

## 1C. 本轮结构性改造验证（2026-11-18）

### 1C.1 验证环境

- Kafka 4.4.0 KRaft 单节点 broker.id=1，127.0.0.1:9092；topic `wms-stock-event`（6 分区，副本 1）、`wms-order-status`（3 分区，副本 1）。
- MySQL 8.0（127.0.0.1:3306）；Redis 5.0.14.1（127.0.0.1:6379，db0，noeviction）。
- 构建产物实测依赖：spring-kafka 3.1.4、kafka-clients 3.6.2（取自 inventory fat jar 的 `BOOT-INF/lib`）；连 broker 4.4.0 协议协商正常，本轮未升级 spring-kafka。
- 三轮真机验证：R2（发件箱 / 认领租约 / 崩溃窗口）、R3（锁回归 / 四缺陷 / 调拨 / 补扫 / OMS）、R4（四缺陷补充回归与全量构建）。

### 1C.2 构建与注册结论

- R2 全量构建 12/12 SUCCESS，耗时 33.309s；R3 12/12 SUCCESS，38.101s；R4 12/12 SUCCESS，31.745s。
- 三轮均 9 服务注册 Eureka 且状态 UP；R3 收尾后实测 8761 与 8081-8089 端口 listen=0。

### 1C.3 七项改造与 OMS 入口结论表

| # | 改造项 | 实测结论 | 证据路径 |
| --- | --- | --- | --- |
| 1 | 事务性发件箱 outbox | 业务事务内落 PENDING 记录，Relay 在事务外投递；Kafka 在线 20 并发 outbox 20 条 SENT、topic 20 条；bootstrap 不可达场景 20 条经回补全部 SENT；库存锁临界区内不调用 Kafka API | `.r2-evidence\05-K1-summary.json`、`07-K2-summary.json`、`11-R2-VERIFICATION-REPORT.md` |
| 2 | 三态认领与租约 | 双实例 8084 / 18084；`claimNew` 影响 1 行抢到、0 行未抢到；过期 PROCESSING 被 `claimExpired` 回收（owner 8084 → 18084，retry 1 → 2）；未过期租约不被抢；retry 达 5 认领 0 行；终态清空 owner/lease | `.sessions-evidence\a-claim-uniqueness.txt`、`c-lease-reclaim.txt`、`d-terminal-writeback.txt`、`11-R2-VERIFICATION-REPORT.md` 4.2 |
| 3 | PENDING 超时扫描与统一时钟 | 候选覆盖 PENDING/FAILED 到期与过期 PROCESSING；PENDING 的 next_retry_time 与 create_time 差值 10000ms，同一应用 now 来源 | `.sessions-evidence\e-pending-timestamp.txt`、`11-R2-VERIFICATION-REPORT.md` 4.3 |
| 4 | 多库位多批次拣货 | 同单两行明细两条任务、两个 requestId；A1 5.000 → 3.000、A2 5.000 → 3.000；流水按库位各 1 行（id 136 / 137），无跨库位扣错 | `.r2-evidence\12-R3-VERIFICATION-REPORT-round3.md` 3.2、`R3-payload\t1-*` |
| 5 | PICKED 禁止直接取消 | PICKED 且存在 picked_qty>0 明细返回 409，文案「已拣货不可直接取消，请走反向入库（退货/调拨退回）回补库存」；未拣货单据取消 CANCELLED | `.r2-evidence\12-R3-VERIFICATION-REPORT-round3.md` 3.3、`R3-payload\t3a-cancel-resp.json` |
| 6 | 调拨在途断链打通 | TRANSFER_OUT 全 SUCCESS 后调 markInTransit，调拨单 CREATED → IN_TRANSIT，outbound_no 回填；404 只记 WARN 与单据级 last_error；重复在途幂等不回退；receive 置 FINISHED；DC02 未做调入仓入库（见 D12） | `.r2-evidence\12-R3-VERIFICATION-REPORT-round3.md` 3.4、`R3-payload\t4-*` / `t4b-*` / `t4x-*`、`D:\wms\wms-backend\.r2-evidence\transfer-service-selftest-report.md` |
| 7 | 单据维度补扫 | 缺任务单据补建 PENDING 后推终态；任务全 SUCCESS 但单据卡住的推 FINISHED；补扫幂等，不重复扣减、不重复发事件；阈值 120s | `.r2-evidence\12-R3-VERIFICATION-REPORT-round3.md` 3.5、`R3-payload\t5-*` |
| 8 | OMS 外部入口 | 首次推送 200；重复推送 409「单据已接收，请勿重复推送: {omsOrderNo}」；主数据不存在 400；PICKED 取消 409 原样透传；回调地址默认空，只记 INFO 跳过 | `.r2-evidence\12-R3-VERIFICATION-REPORT-round3.md` 3.6、`D:\wms\wms-backend\.r2-evidence\oms-phase2-verification-report.md` |

### 1C.4 Kafka 兼容性结论

- 默认 spring-kafka 3.1.4 / kafka-clients 3.6.2 连接 broker 4.4.0：生产者发送、消费者拉取正常，未出现协议协商失败；未升级 spring-kafka。
- 残留限制：broker 持续不可用、无在线兜底实例的严格隔离样本本轮未取得（见 D13）。

## 2. 关键实测数据明细

### 2.1 构建与产物

- Reactor 顺序：wms-backend(1/12) → wms-common(2) → wms-eureka-server(3) → wms-admin-service(4) → wms-inventory-service(5) → wms-inbound-service(6) → wms-outbound-service(7) → wms-transfer-service(8) → wms-stocktake-service(9) → wms-return-service(10) → wms-report-service(11) → wms-integration-service(12)，全部 SUCCESS。
- 启动类核对：`jar-start-class-check.txt` 中 10 个 Start-Class 与模块一一对应。

### 2.2 Eureka 注册清单（最终快照）

| 服务实例 | 端口 | 状态 |
| --- | --- | --- |
| wms-admin-service | 8081 | UP |
| wms-inventory-service | 8082 | UP |
| wms-inbound-service | 8083 | UP |
| wms-outbound-service | 8084 | UP |
| wms-report-service | 8088 | UP |
| wms-integration-service | 8089 | UP |

> transfer(8085)、stocktake(8086)、return(8087) 本轮未启动，未注册。

### 2.3 入库链路前后对照

- 前置（`sql\inbound-before-inventory.txt`）：SKU-001 DC01 在库 15.000（批次 B001），流水 2 行。
- 后置（`sql\inbound-after-check.txt`）：

| 台账行 | 库位 | SKU | 批次 | qty |
| --- | --- | --- | --- | --- |
| 1 | DC01-A-01-01 | SKU-001 | B001 | 15.000 |
| 2 | DC01-A-01-01 | SKU-001 | B-E2E-01 | 20.000 |

- 新增流水：`biz_key=IN:IN1794892935664:SKU-001:DC01-A-01-01:B-E2E-01`，PURCHASE_IN，change +20.000，before 0.000 → after 20.000。
- 入库单 `IN1794892935664`：status=FINISHED；明细 receivedQty=20、putawayQty=20、locationCode=DC01-A-01-01、qcStatus=PASS。

### 2.4 出库成功链路前后对照

- 前置（`sql\outbound-success-before.txt`）：B001 15.000、B-E2E-01 20.000；on-hand API=35.000；流水 3 行。
- 后置（`sql\outbound-success-after-check.txt`）：

| 台账行 | 批次 | qty |
| --- | --- | --- |
| 1 | B001 | 15.000 |
| 2 | B-E2E-01 | 12.000 |

- 补偿任务 id=2：request_id=`OUT:OUT1794893009820:SKU-001:DC01-A-01-01:B-E2E-01`，status=SUCCESS，last_error=NULL，retry_count=0，next_retry_time=NULL。
- 新增流水 id=4：SALE_OUT，change -8.000，before 20.000 → after 12.000。
- 出库单 `OUT1794893009820`：status=FINISHED，pickedQty=8/planQty=8。

### 2.5 补偿闭环三阶段快照

单据 `OUT1794893070048`，SKU-002，批次 B-E2E-02，库位 DC01-P-01，计划/拣货 10。

| 阶段 | 证据 | 单据状态 | 任务状态 | retry_count | next_retry_time | last_error | 台账 / 流水 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 一：确认库存不足 13:24:47 | `sql\comp-phase1-failed.txt` | CONFIRM_FAILED | FAILED | 1 | 2026-11-17 13:25:47 | `code=400, msg=库存不足, skuCode=SKU-002, 现有=0, 需要=10.0000` | 无 SKU-002 台账；扣减流水 0 行 |
| 二：补货后 13:25:38（重试前） | `sql\comp-phase2-restocked.txt` | CONFIRM_FAILED | FAILED | 1 | 2026-11-17 13:25:47 | 同上 | 台账 B-E2E-02 10.000；流水 IN +10.000（id=5） |
| 三：重试成功 13:26:36 | `sql\comp-phase3-success.txt` | FINISHED | SUCCESS | 1 | 2026-11-17 13:25:47 | 保留旧失败文案（见缺陷 D3） | 台账 0.000；流水 id=5 IN +10.000、id=6 OUT -10.000（before 10.000→after 0.000）；SKU-002 流水共 2 行 |

- 轮询记录：13:26:06 FAILED / CONFIRM_FAILED；13:26:21 FAILED / CONFIRM_FAILED；13:26:36 SUCCESS / FINISHED。
- 幂等核对：重试只产生 1 条 OUT 扣减流水，未重复扣减。

### 2.6 CTM 契约实测

| 用例 | 请求 | 响应体（原文） | 库表核对 |
| --- | --- | --- | --- |
| 首次推送 CTM-E2E-001 | POST `/integration/ctm/replenish/order` | `{"code":200,"msg":"ok","data":"OUT1794893255478"}` | external_order id=1，source=CTM，businessType=REPLENISH，warehouse=DC01，status=RECEIVED；出库单 CREATED |
| 重复推送同一 ctmOrderNo | 同上 | HTTP 200，`{"code":409,"msg":"单据已接收，请勿重复推送: CTM-E2E-001","data":null}` | 未生成第二张出库单 |
| 空 wmsOutboundNo 取消 | POST `/integration/ctm/cancel` | `{"code":400,"msg":"wmsOutboundNo 不能为空","data":null}` | — |
| 取消 B（OUT1794893342705 / CTM-E2E-002，取消前任务 FAILED） | POST `/integration/ctm/cancel` | `{"code":200,"msg":"ok","data":null}` | 出库单 CANCELLED；任务 id=4 CANCELLED；13:31:11 跨扫描周期复核仍 CANCELLED；批次 B-E2E-03 流水 0 行 |
| 取消 A（OUT1794893255478 / CTM-E2E-001） | POST `/integration/ctm/cancel` | `{"code":200,"msg":"ok","data":null}` | 出库单 CANCELLED |

### 2.7 报表聚合核对

SQL 直算（`sql\report-direct-dc01.txt`）：

| warehouse | sku | SUM(qty) | SUM(freeze) | available |
| --- | --- | --- | --- | --- |
| DC01 | SKU-001 | 27.000 | 0.000 | 27.000 |
| DC01 | SKU-002 | 0.000 | 0.000 | 0.000 |

API 返回（`http\report-stock-summary-dc01.json`）：SKU-001 onHandQty 27.000 / freezeQty 0.000 / availableQty 27.000；SKU-002 0.000 / 0.000 / 0.000。口径一致。

### 2.8 静态核查结果索引

- yml（`sql\static-yml-check.txt`）：9 服务 name/port/db/zone 全部 (True)。
- 源码越权引用（`sql\static-source-grep.txt`）：InventoryMapper、`com.jim.wms.inventory` import、`wms_inventory` 表名在 inventory 之外均为零命中。
- Feign 归属（`sql\static-source-grep.txt`、`sql\static-imports-olddb.txt`）：4 个 `@FeignClient` 全部位于 `wms-common\...\api`；业务服务仅 import `com.jim.wms.common.api.*`。
- 旧库连接串（`sql\static-imports-olddb.txt`）：无 `jim_wms?` 与字面 `/jim_wms` 直连串。
- 迁移建表（`sql\static-migration-tables.txt`）与数据库层表清单（`sql\static-db-tables.txt`）：归属一致，report 无业务表。
- 直连排查（`sql\static-direct-and-errors.txt`）：唯一 RestTemplate 为 `CtmCallbackClient`，用于出站回调外部 9000，非服务间直连。

### 2.9 本轮并发、锁回归与崩溃窗口数据（2026-11-18）

- **Kafka 在线（K1）**：同一库存维度 20 并发 × 200ms 间隔，20×200，429 为 0，总耗时 1079ms；台账 20.000，流水 20 行，发件箱 20 条 SENT，Kafka topic 20 条。
- **Kafka bootstrap 不可达（K2，9099 模拟，非 broker 进程关停）**：20 并发 × 200ms，20×200，429 为 0，总耗时 1704ms；台账 20.000，流水 20 行，发件箱 20 条 SENT（其中 1 条经回补），topic 20 条。
- **改造前基线**：题面给定默认配置下 Kafka 不可用时 20 并发首轮 17 个 429 / 3 个 200；本轮未在旧代码上复现该基线。
- **Redis 锁 20 并发回归（R3）**：683ms，20×200，台账 20.000，流水 20 行，发件箱 20 条，Kafka 20 条。
- **异常路径**：400 异常后正常请求 13.3ms 恢复；429 有界等待 2018ms；503 快速失败 257ms。
- **崩溃窗口**：confirm 写 PENDING 后、调库存前强杀进程；定时扫描推进单据到 FINISHED，requestId 幂等，库存流水仅 1 行；样例单号 `OUT1794974228602`，任务 id 19，台账 3.000 → 2.000。
- **认领竞争**：双实例 8084 / 18084；`claimNew` 影响行 1（抢到）与 0（未抢到）分布；过期 PROCESSING 回收后 owner 8084 → 18084、retry 1 → 2；未过期租约认领 0 行；retry 达 5 认领 0 行。

## 3. 证据文件绝对路径索引

证据根目录：
`D:\wms\wms-backend\.r2-evidence\legacy-2026-11-17-split-round`

### 3.1 构建与产物

- 构建完整日志：`D:\wms\wms-backend\.r2-evidence\legacy-2026-11-17-split-round\build.log`
- 构建耗时：`D:\wms\wms-backend\.r2-evidence\legacy-2026-11-17-split-round\build-elapsed.txt`
- jar Main-Class 核对：`D:\wms\wms-backend\.r2-evidence\legacy-2026-11-17-split-round\jar-manifest-check.txt`
- jar Start-Class 核对：`D:\wms\wms-backend\.r2-evidence\legacy-2026-11-17-split-round\jar-start-class-check.txt`

### 3.2 服务运行日志（legacy-2026-11-17-split-round\logs）

- `wms-eureka-server.out.log` / `.err.log` / `.pid`
- `wms-admin-service.out.log` / `.err.log` / `.pid`
- `wms-inventory-service.out.log` / `.err.log` / `.pid`
- `wms-inbound-service.out.log` / `.err.log` / `.pid`
- `wms-outbound-service.out.log` / `.err.log` / `.pid`
- `wms-report-service.out.log` / `.err.log` / `.pid`
- `wms-integration-service.out.log` / `.err.log` / `.pid`

### 3.3 HTTP 原始响应（legacy-2026-11-17-split-round\http）

- Eureka：`eureka-apps.json`、`eureka-apps-final.json`
- 入库：`inbound-01-create.json`、`inbound-02-receive.json`、`inbound-03-qc.json`、`inbound-04-putaway.json`、`inventory-onhand-before.txt`
- 出库成功：`out-success-01-create.json`、`out-success-02-pick.json`、`out-success-03-confirm.json`
- 补偿闭环：`comp-01-create.json`、`comp-02-pick.json`、`comp-03-confirm-fail.json`、`comp-restock-01-create.json`、`comp-restock-02-receive.json`、`comp-restock-03-qc.json`、`comp-restock-04-putaway.json`、`comp-restock-no.txt`、`ctm-B-no.txt`、`ctm-B-pick.json`、`ctm-B-confirm.json`
- CTM：`ctm-01-replenish-first.json`、`ctm-02-replenish-duplicate.json`、`ctm-03-cancel-blank.json`、`ctm-04-replenish-B.json`、`ctm-05-cancel-B.json`、`ctm-06-cancel-A.json`
- 报表：`report-stock-summary-dc01.json`、`report-stock-summary-all.json`
- 只读契约扫描：`get-contract-sweep.txt`、`get-contract-sweep-fixed.txt`、`sweep-*.json`

### 3.4 SQL 结果（legacy-2026-11-17-split-round\sql）

- 基线：`baseline-rows.txt`、`inventory-residual-snapshot.txt`
- 入库：`inbound-before-inventory.txt`、`inbound-after-check.txt`
- 出库成功：`outbound-success-before.txt`、`outbound-success-after-check.txt`
- 补偿：`comp-before-sku002.txt`、`comp-phase1-failed.txt`、`comp-phase2-restocked.txt`、`comp-phase3-success.txt`
- CTM：`ctm-external-order-after.txt`、`ctm-B-before-cancel.txt`、`ctm-after-cancel.txt`、`ctm-B-after-scan.txt`
- 报表：`report-direct-dc01.txt`
- 静态核查：`static-yml-check.txt`、`static-source-grep.txt`、`static-imports-olddb.txt`、`static-migration-tables.txt`、`static-db-tables.txt`、`static-direct-and-errors.txt`
- 收尾：`pre-clean-dump.txt`、`post-clean-rowcount.txt`

### 3.5 第二轮回归证据（legacy-2026-11-17-split-round\regression）

- 构建：`regression\build.log`、`regression\build-elapsed.txt`。
- 运行日志 / PID：`regression\logs\wms-*.out.log` / `.err.log` / `.pid`（eureka、admin、inventory、outbound、integration）。
- HTTP 原始请求与响应（`regression\http`）：
  - 补货：`01-restock.req.json` / `01-restock.resp.txt`
  - CTM TRANSFER：`02-ctm-transfer.req.json` / `02-ctm-transfer.resp.txt`；拣货 `03-pick.*`；确认 `04-confirm.*`
  - 409 重复推送：`05-repeat.req.json` / `05-repeat.resp.txt`；400 空号取消：`06-cancel-empty.*`
  - 第二单：`07-order2.*`、`08-order2-pick.*`、`09-order2-confirm.*`、`10-cancel-order2.*`
  - 端点清单与探测：`11-inventory-endpoints.md`、`12-inv-query.resp.txt`、`13-inv-onhand.resp.txt`
  - Eureka：`eureka-apps.json`
- SQL（`regression\sql`）：`00-baseline.sql`、`01-after-restock.*`、`02-after-ctm.*`、`03-after-confirm.*`、`04-order2-failed.*`、`05-order2-cancelled.*`、`06-order2-after-scan.*`、`07-pre-clean-rows.*`、`08-cleanup.sql`、`09-post-clean-rows.*`、`10-flyway-history.*`；静态复扫 `static-yml-regression.txt`、`static-regression-check.txt`、`static-olddb-precise.txt`。

### 3.6 第三轮 Redis 锁验证证据（根目录见本节下一行）

本轮独立证据根：`D:\wms\wms-backend\.r2-evidence\legacy-2026-11-17-lock-round`

- 构建：`build.log`、`build-elapsed.txt`。
- 运行日志 / PID（`logs`）：`wms-eureka-server.out.log/.err.log/.pid`、`wms-inventory-service.out.log/.err.log/.pid`、`wms-inventory-18082.out.log/.err.log/.pid`；归档 `inventory-run1-default-kafka.out.log/.err.log`、`inventory-run2-wait2s.out.log`、`inventory-run3-wait10s.out.log`、`inventory-18082-false.out.log`；进程清单 `java-processes-before-stop.txt`；端口释放 `ports-after-stop.txt`。
- HTTP 原始请求 / 响应（`http`）：
  - 注册：`eureka-apps.json`、`eureka-apps-summary.txt`
  - 预热加解锁：`warmup.req.json` / `warmup.resp.txt` / `warmup.resp.meta.txt`、`warmup-lock-lines.txt`；`warmup2.*`、`warmup3.*`、`probe-warm.*`
  - 并发 A：`conc-a-results.json`；并发 B：`conc-b-results.json`；B 首轮归档 `conc-b-results-run1-tight.json`；并发脚本 `concurrency-runner.ps1`
  - 异常路径：`neg-01.req.json` / `neg-01.resp.txt` / `neg-01.resp.meta.txt`；`ok-after-neg.req.json` / `ok-after-neg.resp.txt` / `ok-after-neg.resp.meta.txt`
  - 429：`wait429.req.json` / `wait429.resp.txt` / `.meta.txt`、`wait429b.req.json` / `wait429b.resp.txt`
  - 503：`test503.req.json` / `test503.resp.txt` / `test503.resp.meta.txt` / `test503-logline.txt`
  - 兜底：`testfallback.req.json` / `testfallback.resp.txt` / `testfallback.resp.meta.txt` / `testfallback-logline.txt`
  - 启动参数记录：`8082-startup-override.txt`
- SQL（`sql`）：`baseline-before-warmup.txt`、`conc-baseline.txt`、`conc-a-baseline.txt`、`conc-a-after.txt`、`conc-b-baseline.txt`、`conc-b-after.txt`、`pre-clean-smoke-dump.txt`、`post-clean-counts.txt`、`post-clean-table-counts.txt`。
- Redis（`redis`）：`429-hold-lock.txt`、`429-hold-lock-retry.txt`、`429-after-del.txt`、`final-redis-state.txt`。

### 3.7 本轮结构性改造证据（2026-11-18）

证据根目录 `D:\wms\wms-backend\.r2-evidence\` 与 `D:\wms\wms-backend\.sessions-evidence\`：

- 轮次报告：`.r2-evidence\11-R2-VERIFICATION-REPORT.md`、`.r2-evidence\12-R3-VERIFICATION-REPORT-round3.md`、`.r2-evidence\R4-VERIFICATION-REPORT-round4.md`、`.sessions-evidence\round2\REPORT-ROUND2.md`、`.sessions-evidence\REFACTOR-REPORT.md`。
- 归档入 `.r2-evidence` 的专题报告：`D:\wms\wms-backend\.r2-evidence\oms-phase2-verification-report.md`（OMS 阶段二验证）、`D:\wms\wms-backend\.r2-evidence\transfer-service-selftest-report.md`（调拨服务改造自验证）、`D:\wms\wms-backend\.r2-evidence\R2b-confirm-fix-report.md`（健康链路 confirm 修复回归，含 `TIME_TOTAL=2.303098s`）、`D:\wms\wms-backend\.r2-evidence\R2b-a-confirm-resp.txt`（该次 confirm 原始响应）。
- 构建：`.r2-evidence\R3-01-build.log`、`R4-full-build.log`、`R4-permodule-compile.log`、`01-build-r2.log`。
- 并发与锁：`.r2-evidence\05-K1-results.csv`、`05-K1-summary.json`、`07-K2-summary.json`、`R3-06-lock-summary.json`、`R3-06-lock-raw.txt`；脚本 `concurrent-k1.ps1`、`ConcurrentRunner.cs` / `ConcurrentRunner.exe`。
- 认领 / 租约 / 时钟：`.sessions-evidence\a-claim-uniqueness.sql` / `.txt`、`c-lease-reclaim.sql` / `.txt`、`d-terminal-writeback.sql` / `.txt`、`e-pending-timestamp.txt`。
- 缺陷回归请求 / 响应：`.r2-evidence\R3-payload\`（t1、t3a、t3b、t4、t4b、t4x、t5、t8、t9、R3-SEED-*）、`R4-payload\`（d1、d2、d3）。
- 运行日志：`.r2-evidence\logs\`、`R3-logs\`、`R4-logs\`。
- 清场：`.r2-evidence\R3-10-cleanup.sql`、`R4-cleanup.sql`、`R3-10-cleanup-before.txt` / `after.txt`、`R4-cleanup-before.txt` / `after.txt`、`.sessions-evidence\01-orders-before-cleanup.txt` 等。

## 4. 缺陷与观察项清单

> 按任务规则：本轮未修改任何业务代码、契约 DTO 与 wms-common；以下问题仅记录，待对应负责人修复。

### D1（误判，已更正）— 库存流水查询端点：基线本就不存在，新工程无缺失

> **更正结论（第二轮复核）**：经 Grep 核对 `docs/MONOLITH-BASELINE.md`，`stock-history` / `stockHistory` / `StockHistory` 全文 **0 命中**；基线 2.2 节 InventoryController 仅 `/query`、`/on-hand`、`/freeze` 三个端点；单体备份 `wms-inventory/.../InventoryController.java` 口径一致。原第一轮判定「缺基线 T-06 stock-history」系误判。**新工程对外端口契约与基线逐条一致，无缺失**，不为 inventory 新增该端点。

- 服务 / 文件：wms-inventory-service
  `wms-inventory-service\src\main\java\com\jim\wms\inventory\controller\InventoryController.java`
- 第一轮误判记录（保留备查）：曾访问 `GET /api/inventory/stock-history?...` 返回 `NoResourceFoundException`；但基线本就未定义此端点，故不构成缺口。
- 第二轮端点清单证据：`regression\http\11-inventory-endpoints.md`、`regression\http\12-inv-query.resp.txt`、`regression\http\13-inv-onhand.resp.txt`。

### D2（与单体一致，非本次引入）— 缺参 / 未知路由异常码未区分 400 / 404

> **更正结论（第二轮复核）**：备份旧单体 `D:\wms\wms-backend-monolith-backup\wms-framework\src\main\java\com\jim\wms\framework\exception\GlobalExceptionHandler.java` 只有 BusinessException、MethodArgumentNotValidException/BindException、Exception 三类处理器，未知异常兜底 `Result.fail(500, "系统内部错误: " + ...)`。新工程行为与单体**完全一致**，属于沿用旧实现，**不构成对单体契约的突破**；列为可选优化项，不计为回归缺陷。

- 文件：`wms-common\src\main\java\com\jim\wms\common\exception\GlobalExceptionHandler.java:35-39`
- 现象：`MissingServletRequestParameterException`、`NoResourceFoundException` 落入兜底 `Exception` 处理器，body.code 统一记为 500，msg 带异常类名；HTTP 状态码仍为 200。
- 可选期望：缺参返回 400、未知路由返回 404（或在 body.code 中区分），便于调用方与网关识别。
- 证据：`http\sweep-inv-onhand-nosku.json`（缺 skuCode → code=500 MissingServletRequestParameterException）、`http\sweep-admin-warehouses.json`（误用 `/api/admin/...` → NoResourceFoundException，注：公共主数据正确前缀为 `/api/master`）。

### D3（已修复并复测通过）— SUCCESS 补偿任务残留旧失败文案

- 原现象：MyBatis-Plus `updateById` 默认忽略 null 字段，`lastError=null` 不生效；任务转 SUCCESS 后仍保留首次失败的 `last_error` 文案。第一轮实测见 `sql\comp-phase3-success.txt`。
- 修复：`wms-outbound-service\...\impl\OutboundServiceImpl.java:305-315` `markSuccess` 改用 `Wrappers.lambdaUpdate()` 显式 `.set(OutboundStockTask::getLastError, null)`（规避 MP updateById 忽略 null 字段）。
- 第二轮复测：TRANSFER 链路任务 status=SUCCESS，`(last_error IS NULL)=1`，`last_error=NULL`，retry_count=0，next_retry_time=NULL。
- 证据：`regression\sql\03-after-confirm.txt`、`regression\sql\03-after-confirm.sql`。

### D4（已修复并实测通过）— CTM 调拨类型映射

- 原风险：integration 曾预把 `TRANSFER` 映射成 `TRANSFER_OUT` 透传，outbound 又按字面 `"TRANSFER"` 比较，`TRANSFER_OUT` 不匹配而错归 `REPLENISH`，流水误记 `SALE_OUT`。
- 修复：integration `CtmReplenishController.java:60` 原样透传 `dto.getOrderType()`；outbound `OutboundServiceImpl.java:194-197` 幂等映射 `TRANSFER`/`TRANSFER_OUT → TRANSFER_OUT`，其余 → `REPLENISH`；流水类型 `:259` 按 `TRANSFER_OUT` 判定。
- 第二轮 E2E 实测：`orderType=TRANSFER` 推送后出库单 `order_type=TRANSFER_OUT`，确认流水 `business_type=TRANSFER_OUT`（非 SALE_OUT），单据 FINISHED，任务 SUCCESS。
- 证据：`regression\http\02-ctm-transfer.resp.txt`、`regression\sql\02-after-ctm.txt`、`regression\sql\03-after-confirm.txt`；详见本报告 1A.4 节。

### D5（已部分接线）— OMS 路径已消费 AdminApi 主数据校验

- 现状：`AdminApi` 契约与 admin 侧实现保留；integration 的 OMS 建单流程已注入 AdminApi 做仓库 / 库位 / 商品校验，主数据不存在返回 400（实测三条 OMS 主数据样例）。
- 未覆盖：CTM 补货 / 调拨路径仍未走主数据 Feign 校验，沿用旧单体语义。
- 证据：`.r2-evidence\R3-payload\t9-oms-order-resp1.json`、`t9-oms-bad-resp.json`、`D:\wms\wms-backend\.r2-evidence\oms-phase2-verification-report.md`。

### D6（观察，非代码缺陷）— 文档注释残留旧包名

- 文件：`wms-common\...\dto\InventoryView.java:10`，Javadoc 提及旧实体包名 `com.jim.wms.inventory.entity.Inventory`；仅注释，无 import、无编译引用。

### D7（已修复并复测）— 事件移出库存锁临界区，Kafka 不可用不再拉长持锁触发 429

- 旧现象：见 1B.10 O1；首轮默认配置 20 并发 17×429 / 3×200（题面基线，本轮未在旧代码上复现）。
- 修法：事件改事务性发件箱，业务事务内只落 PENDING 记录，Kafka 发送由 Relay 在事务外、锁外完成；临界区内不调用 Kafka API。
- 复测：Kafka 在线 20×200、429 为 0；bootstrap 不可达（9099 模拟）20×200、429 为 0；发件箱记录最终全部 SENT（见 1C.3 第 1 行、2.9 节）。
- 残留：broker 持续不可用、无在线兜底实例的严格隔离样本未取得（见 D13）。
- 证据：`.r2-evidence\05-K1-summary.json`、`07-K2-summary.json`、`11-R2-VERIFICATION-REPORT.md`。

### D8（已修复）— 健康链路 confirm 误报 CONFIRM_FAILED

- 现象：confirm 返回 200 后单据先落 CONFIRM_FAILED、任务 PENDING，需等 30s 扫描周期才被认领扣减推到 FINISHED。
- 修法：`claimFirstAttempt` 首次认领（仅 PENDING 且 retry_count 为 0，不判到期）+ dispatch 三态（未认领 / 成功 / 远程失败）+ 结束重查全部任务 + 终态幂等守卫。
- 实测：健康链路 confirm 耗时 2.303s 返回后立即查库即 FINISHED，未观察到 CONFIRM_FAILED 中间态（样本 `OUT1794976876513`，任务 23/24 SUCCESS，流水 130/131 各 1 行）；终态幂等守卫样本 `OUT1794977962652` 发件箱仅 1 条 SENT。
- 证据：2.303s 出自 `D:\wms\wms-backend\.r2-evidence\R2b-confirm-fix-report.md`（`TIME_TOTAL=2.303098s`）与 `D:\wms\wms-backend\.r2-evidence\R2b-a-confirm-resp.txt`（原始 confirm 响应，body.code 200）；终态幂等守卫与 1 条 SENT 见 `.r2-evidence\12-R3-VERIFICATION-REPORT-round3.md` 3.1。

### D9（已修复）— 库存空批次维度不匹配导致同 SKU 同库位重复建行

- 现象：空批次以空串与 NULL 两种口径落库，同 SKU 同库位被拆成多行。
- 修法：入口归一空串 / 全空格为 NULL，查询条件与落库值口径一致。
- 实测：三笔 +1 合并为 1 行，qty 3.000。
- 证据：`.r2-evidence\R4-VERIFICATION-REPORT-round4.md` D1、`R4-payload\d1-*`。

### D10（已修复）— CTM 补货单明细无库位导致按库位拣货 404

- 修法：库位可选；唯一可拣行直接拣；多行候选返回 400 带候选；明细与入参都无库位时按唯一可用库存库位回填。
- 实测：无库位 CTM 单拣货成功；多行歧义返回 400 带候选库位。
- 证据：`.r2-evidence\R4-VERIFICATION-REPORT-round4.md` D2、`R4-payload\d2-*`。

### D11（已修复）— CTM 建单未回填 wms_external_order.outbound_no

- 修法：CTM 建单 200 后与 OMS 路径一致回填 outbound_no。
- 实测：建单响应与外部订单记录均可查到回填的出库单号。
- 证据：`.r2-evidence\R4-VERIFICATION-REPORT-round4.md` D2、`R4-payload\d2-ctm-order-resp.json`。

### D12（现状未改，范围外）— 调拨收货只置 FINISHED，未做调入仓入库

- 现状：`receive` 仅把调拨单 IN_TRANSIT 置 FINISHED，未接入 inventory 写者做调入仓入库；实测 DC02 无库存增加。
- 影响：调出仓已扣、调入仓未加，期间库存分布账面不闭环；需后续由调入收货流程经 InventoryApi 回补。
- 证据：`.r2-evidence\12-R3-VERIFICATION-REPORT-round3.md` 3.4、`D:\wms\wms-backend\.r2-evidence\transfer-service-selftest-report.md`。

### D13（验证限制）— Kafka 不可用的长期 PENDING/FAILED 未取得严格隔离样本

- 现状：本轮「停 Kafka」以不可达 bootstrap（9099）模拟，同库主实例 Relay 仍在线抢投兜底；未构造 broker 持续关停、无在线 Relay 兜底的严格隔离场景，发件箱长期滞留 PENDING/FAILED 的行为未单独采样。
- 推断：Relay 失败重试与租约回收逻辑按代码路径设计，长期滞留表现需在隔离环境补测。

## 5. 未验证项与环境限制

| 项 | 现状 | 说明 |
| --- | --- | --- |
| Kafka 真实投递 / 消费 | 已实测（见 1C.4） | broker 4.4.0 在线；spring-kafka 3.1.4 / kafka-clients 3.6.2 协议协商正常；两 topic 生产与消费条数、key、字段口径核对一致。残留：broker 持续不可用的严格隔离样本未取得（D13）。 |
| Redis 真实分布式锁 | 已实测通过（见 1B 节与 2.9 节） | 6379 在线；启动 PING=PONG；token/Lua 释放、20 并发 A/B 互斥不丢失更新、429 有界等待、503 快速失败、兜底开关均已实测。多实例横向扩缩容下的分布式互斥未在本轮多实例拓扑中复测。 |
| 真实 CTM 回调 | 未验证 | 9000 不存在；`CtmCallbackClient` POST `http://127.0.0.1:9000/ctm/wms/order/status` `Connection refused`，记 ERROR 待补偿；回调重试与对账未测。 |
| Eureka 高可用 / 多实例 | 未验证 | 单注册中心、每服务单实例。 |
| 并发与压测范围 | 部分验证 | 单库存维度 20 并发互斥与不丢失更新已实测（1B.5、2.9）；未做全链路压测、超卖压测、重复推送 / 回调压测，也未评估 9 服务同时在线的内存与端口压力。 |
| transfer / stocktake / return / report / inbound 业务链路 | 部分验证 | transfer 在途通知与收货状态流转已 E2E 实测（1C.3 第 6 行）；stocktake、return、report、inbound 本轮无业务数据，仅随启动注册与 Redis 就绪验证，对外端点与 Feign 成功链路未做链路级 E2E。 |
| 发件箱积压与消费者侧对账 | 部分验证 | 生产侧投递、回补、SENDING 租约回收已实测；消费者侧积压、重试与下游镜像对账本轮未覆盖。 |
| 多实例长稳认领与时钟漂移 | 部分验证 | 双实例短时认领竞争、过期租约回收已实测；长稳多实例压测、实例间时钟漂移边界未压测。 |
| 调拨收货调入仓入库 | 现状未改（D12） | receive 只置 FINISHED，未做调入仓入库；账面缺口见 D12。 |
| 单据补扫 120s 窗口 | 部分验证 | 缺任务单与卡住单两类补扫已实测；120s 窗口内的状态滞后表现与长稳运行未压测。 |
| OMS 回调真实联调 | 未验证 | callback-url 默认空，仅验证未配置只记 INFO 跳过；已配置后的 HTTP 投递、失败重试与对账未实测。 |

## 6. 收尾状态

- 本阶段启动的 WMS java 进程已全部停止；8761、8081-8089 端口均已释放（`logs\*.pid`）。
- 冒烟业务数据已清理，各库业务表 0 行；admin 种子数据保留（warehouse=2、area=4、location=5、sku=2）；各库 `flyway_schema_history` 保留。
- 旧单体库 `jim_wms` 未被任何新服务直连，本轮未改动其数据。
- 最终行数证据：`sql\post-clean-rowcount.txt`。
- **第二轮收尾（回归）**：5 个 WMS java 进程（eureka/admin/inventory/outbound/integration）已停止，端口全 free；仅保留与 WMS 无关的 IDE Java 语言服务器；业务表清零、admin 种子与 Flyway 历史保留。证据：`regression\sql\07-pre-clean-rows.txt`、`regression\sql\09-post-clean-rows.txt`、`regression\sql\10-flyway-history.txt`、`regression\logs\*.pid`。
- **第三轮收尾（Redis 锁验证）**：eureka 8761、inventory 8082、临时实例 18082 已全部停止，8761/8082/18082 listen=0；保留 IDE Java 语言服务器（PID 8272）。SKU-LOCK-01 冒烟台账与流水已清理（inventory 台账 0、流水 0），admin 种子与各库 Flyway 历史保留；Redis `KEYS wms:stock:*` 空、`DBSIZE=0`。证据：1B.11 节、`sql\post-clean-table-counts.txt`、`redis\final-redis-state.txt`、`logs\ports-after-stop.txt`。
- **第四轮收尾（结构性改造，2026-11-18）**：三轮真机验证完成后，自启 WMS Java 进程已全部停止，8761 与 8081-8089 端口空闲；MySQL（3306）、Redis（6379）、Kafka（9092）保持运行未停；IDE Java 语言服务器保留未杀。冒烟业务数据已清理，各库 Flyway 迁移历史与 admin 种子数据保留。证据：`.r2-evidence\R3-10-cleanup-before.txt` / `after.txt`、`R4-cleanup-before.txt` / `after.txt`、`.sessions-evidence\02-items-before-cleanup.txt`、`03-tasks-before-cleanup.txt`、`04-outbox-before-cleanup.txt`、`05-flyway-history.txt`。
