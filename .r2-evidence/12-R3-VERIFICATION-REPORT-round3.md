# R3（round3 / 收尾轮）全栈真机验证报告

- 项目根：`D:\wms\wms-backend`
- 验证时间：2026-11-18 12:56 – 13:16（Asia/Shanghai）
- 范围：只取证，未改任何代码 / yml / 迁移 / 文档；发现缺陷原样记录，未顺手修复。
- 中间件全程保持运行：Kafka 4.4.0 KRaft `127.0.0.1:9092`（PID 29640）、MySQL 3306（PID 19848）、Redis 6379（PID 28328）。IDE Java LSP（PID 8272）未被终止。
- 证据目录：`D:\wms\wms-backend\.r2-evidence\`（本轮文件前缀 `R3-`，未覆盖第一轮 `11-R2-VERIFICATION-REPORT.md`）。

---

## 0. 构建结论与 jar 形态

- 命令：`mvn -B -DskipTests clean package`（新日志 `R3-01-build.log`，未覆盖第一轮 `.build-full-r2.log`）。
- 结果：`BUILD SUCCESS`，reactor **12/12 SUCCESS**，`Total time: 38.101 s`，`Finished at: 2026-11-18T12:56:13+08:00`，日志无 `[ERROR]`。
- jar 形态（本轮无瘦 jar，无需 repackage）：

| 模块 | jar 大小 | 形态 |
|---|---|---|
| wms-eureka-server | 54.7 MB | fat |
| wms-admin-service | 67.4 MB | fat |
| wms-inventory-service | 83.3 MB | fat |
| wms-inbound-service | 83.3 MB | fat |
| wms-outbound-service | 83.3 MB | fat |
| wms-transfer-service | 67.4 MB | fat |
| wms-stocktake-service | 67.4 MB | fat |
| wms-return-service | 67.4 MB | fat |
| wms-report-service | 67.4 MB | fat |
| wms-integration-service | 67.4 MB | fat |

---

## 1. 全栈启动注册

- 启动顺序：eureka(8761) → admin(8081)、inventory(8082)、inbound(8083)、outbound(8084)、transfer(8085)、stocktake(8086)、return(8087)、report(8088)、integration(8089)，全部默认配置，未覆盖 wait-timeout / 扫描间隔 / Kafka bootstrap / 租约等任何参数。
- 原始取证：`R3-02-eureka-apps.json`、`R3-02-eureka-apps.csv`；服务日志 `R3-logs\<name>.out.log/.err.log`。
- Eureka `apps__hashcode=UP_9_`，9 个实例全部 UP：

| 应用 | 端口 | 状态 |
|---|---|---|
| WMS-ADMIN-SERVICE | 8081 | UP |
| WMS-INVENTORY-SERVICE | 8082 | UP |
| WMS-INBOUND-SERVICE | 8083 | UP |
| WMS-OUTBOUND-SERVICE | 8084 | UP |
| WMS-TRANSFER-SERVICE | 8085 | UP |
| WMS-STOCKTAKE-SERVICE | 8086 | UP |
| WMS-RETURN-SERVICE | 8087 | UP |
| WMS-REPORT-SERVICE | 8088 | UP |
| WMS-INTEGRATION-SERVICE | 8089 | UP |

- Redis 锁通道：9 个服务启动日志各出现 1 处「Redis 锁通道就绪（...）PING=PONG」；inventory / inbound / outbound 无「无 KafkaTemplate 不投递」类 WARN（kafkaWarn=0/0/0）。

---

## 2. 多库位多批次拣货扣对库位（必测项 1）

场景：同一 SKU-001 在两个库位、两个批次各备 5.000，建一张含两条明细的出库单，分别按 `locationCode + batchNo` 拣货后 confirm。

- 备库（`/internal/inventory/change-stock`，requestId 唯一）：
  - `DC01-A-01-01 / B-R3-A1` 5.000（`R3-SEED-A1`）
  - `DC01-A-01-02 / B-R3-A2` 5.000（`R3-SEED-A2`）
- 出库单：`OUT1794977962652`（sourceNo `CTM-R3-T1`），明细两行：
  - id32：SKU-001 / DC01-A-01-01 / B-R3-A1 / plan 2
  - id33：SKU-001 / DC01-A-01-02 / B-R3-A2 / plan 2
- 取证：`R3-payload\t1-order.json`、`t1-order-resp.json`、`t1-pick1.json`、`t1-pick2.json`、`t1-confirm.json`、`t1-confirm-resp.json`。

核对结果：

| 维度 | 期望 | 实际 |
|---|---|---|
| 明细唯一性 | 同 SKU 不同库位两行，不合并 | 两行（id32 / id33），库位+批次各自独立 |
| 补偿任务 | 两条任务、两个不同 requestId | 任务 id30 / id31，requestId 分别为 `OUT:OUT1794977962652:SKU-001:DC01-A-01-01:B-R3-A1`、`OUT:OUT1794977962652:SKU-001:DC01-A-01-02:B-R3-A2`，均 SUCCESS |
| 台账按库位扣减 | 各自库位 5→3 | A-01-01/B-R3-A1 5.000→3.000；A-01-02/B-R3-A2 5.000→3.000 |
| 库存流水 | 按库位各 1 行，无跨库位扣错/重复扣 | 流水 id136（A1，-2，5→3）、id137（A2，-2，5→3），biz_key 与任务 requestId 一致 |

Kafka `wms-stock-event` 两条（`R3-03-kafka-stock-beginning.txt` / `R3-07-kafka-stock-after.txt`）：

```
DC01:SKU-001|{"eventId":"2122917793916653568","eventType":"OUTBOUND","warehouseCode":"DC01","locationCode":"DC01-A-01-01","skuCode":"SKU-001","batchNo":"B-R3-A1","businessNo":"OUT1794977962652","changeQty":-2.0000,"beforeQty":5.000,"afterQty":3.0000,...}
DC01:SKU-001|{"eventId":"2122917794415775744","eventType":"OUTBOUND","warehouseCode":"DC01","locationCode":"DC01-A-01-02","skuCode":"SKU-001","batchNo":"B-R3-A2","businessNo":"OUT1794977962652","changeQty":-2.0000,"beforeQty":5.000,"afterQty":3.0000,...}
```

事件 `locationCode / batchNo / changeQty / beforeQty / afterQty` 与流水逐字段一致；条数 = 流水条数（2）。

---

## 3. 确认语义（新修复，必测项 2）

健康链路：`OUT1794977962652` 两条明细拣齐后 confirm。

- confirm 返回 HTTP 200，响应 `{"code":200,"msg":"ok","data":null}`；返回后立即查库：`status=FINISHED`，**未观察到 CONFIRM_FAILED 中间态**。
- 同链路另一笔部分出库单 `OUT1794978157844` confirm 耗时 **196 ms**；T4 调拨单 `OUT1794978267816` confirm 耗时 **326 ms**。
- 终态幂等守卫核对（`OUT1794977962652`）：
  - `wms-order-status` 发件箱仅 **1 条** SENT（outbox id13，topic `wms-order-status`）。
  - Kafka `wms-order-status` 仅 **1 条**该单 FINISHED（`R3-04-kafka-order-status.txt`）：
    ```
    OUT1794977962652|{"orderNo":"OUT1794977962652","message":"出库完成","status":"FINISHED"}
    ```
  - 消息体字段 `orderNo / status / message` 齐全；**Kafka key = orderNo**（`OUT1794977962652`），由 `--property print.key=true` 证明。
- OMS 单 `OUT1794978758271` 同样：confirm 后立即 FINISHED，outbox 仅 1 条 SENT（id21）。

结论：终态幂等守卫未造成漏发或重复发；健康链路 confirm 后直接 FINISHED。

---

## 4. PICKED 禁止直接取消（必测项 3）

### 4.1 已拣货（PICKED）取消 → 409

- 单据 `OUT1794978157844`：plan 5，拣 2，confirm 后状态 **PICKED**（任务 SUCCESS，台账 B-R3-A3 5.000→3.000，流水 1 行 id139）。
- 调 `/api/outbound/cancel`：HTTP 200 包装，**body.code=409**，原文（UTF-8 解码）：
  ```
  {"code":409,"msg":"已拣货不可直接取消，请走反向入库（退货/调拨退回）回补库存","data":null}
  ```
- 取消后单据仍 **PICKED**，库存仍 **3.000**，未回滚、未再扣。取证 `t3a-cancel.json` / `t3a-cancel-resp.json`。

### 4.2 未拣货单据取消 → CANCELLED

- 单据 `OUT1794978192470`（CREATED，未拣货）取消：body.code=200，单据 **CANCELLED**；无补偿任务；`wms_inventory_log` 该单 **0 行**（未扣减）。取证 `t3b-order-resp.json`、`t3b-cancel-resp.json`。
- 取消后未再被扫描扣减（任务表无该行，流水 0）。

---

## 5. 调拨在途断链（缺陷三，必测项 4）

### 5.1 正向：TRANSFER_OUT 出库完成 → 调拨单在途

- 调拨单 `TR1794978231092`（sourceNo `CTM-R3-T4`，DC01→DC02，SKU-001/B-R3-A4/qty2，初始 CREATED，outboundNo 空）。
- 出库单 `OUT1794978267816`（TRANSFER_OUT，sourceNo `CTM-R3-T4`，targetWarehouseCode DC02），拣 2、confirm 326 ms → **FINISHED**。
- 调拨单由 CREATED → **IN_TRANSIT**，并回填 `outbound_no=OUT1794978267816`；调出仓 B-R3-A4 3.000→1.000。
- 匹配路径：outbound 传 `transferNo=sourceNo(CTM-R3-T4)`，transfer 侧先按 transfer_no 未命中、**按 source_no 兜底命中**（`TransferServiceImpl.resolve` 112-120）。

### 5.2 边界①：transfer 不存在 → 只 WARN + 单据级 last_error，单据仍 FINISHED

- 出库单 `OUT1794978302012`（TRANSFER_OUT，sourceNo `CTM-R3-T4X`，库内无对应调拨单），拣 2、confirm → **FINISHED**。
- 单据 `last_error`：
  ```
  markInTransit code=404, msg=调拨单不存在: CTM-R3-T4X
  ```
- outbound 日志 WARN（`R3-logs\outbound.out.log`）：
  ```
  调拨在途通知未成功（不阻断出库单据）, outboundNo=OUT1794978302012, transferNo=CTM-R3-T4X, code=404, msg=调拨单不存在: CTM-R3-T4X
  ```
  出库单据未被失败阻断。

### 5.3 边界②：outbound_no 为空按 source_no 兜底

- 直接调 `/internal/transfer/in-transit`，`transferNo=CTM-R3-T4`、`outboundNo=""`：仍按 source_no 命中 `TR1794978231092`，返回 200，状态 IN_TRANSIT，已有 outboundNo 不被空值覆盖。

### 5.4 边界③：重复 markInTransit 幂等，不回退

- 对 IN_TRANSIT 的 `TR1794978231092` 再次调 in-transit：返回 200，状态仍 **IN_TRANSIT**，outboundNo 保留，未回退到 CREATED。

### 5.5 调入收货

- `/internal/transfer/receive`（transferNo `CTM-R3-T4`）→ 调拨单 **FINISHED**。
- **现状如实记录**：收货只把调拨单置 FINISHED，**未做调入仓（DC02）入库**；DC02 库存表无任何 SKU-001 行（与 `TransferOrder` 实体注释一致：本服务只维护跟踪单，不直接调库存）。

---

## 6. 单据维度补扫（缺陷四，必测项 5）

阈值：单据补扫 120s，任务扫描 30s。两单均用 SQL 置 `CONFIRMING`、`update_time = NOW()-130s` 构造超阈值卡住状态。

### 6.1 缺任务单 `OUT1794978366749`（CTM-R3-T5A，B-R3-A6）

- 构造时：CONFIRMING，**无任务行**，明细 pickedQty=2。
- 13:07:08 补扫日志：`单据补扫: 停留在 CONFIRMING 超阈值单据数=2`；该单补建 PENDING 任务（requestId `OUT:OUT1794978366749:SKU-001:DC01-A-01-02:B-R3-A6`），当轮因任务未成功先回 `CONFIRM_FAILED`（WARN：`仍有未成功任务, 回 CONFIRM_FAILED 交任务级重试`）。
- 任务级扫描 13:07:38：`补偿扫描本轮结束: 候选=1, 认领=1, 回写=1`，任务 SUCCESS。
- 下一轮补扫推到 **FINISHED**；台账 B-R3-A6 2.000→0.000；流水仅 **1 行**（id147，-2，2→0，biz_key 唯一），无重复扣减。

### 6.2 任务全 SUCCESS 但单据未推进 `OUT1794978366820`（CTM-R3-T5B，B-R3-A7）

- 先真实 confirm 成功（任务 SUCCESS、流水 1 行），再 SQL 把单据回置 CONFIRMING、update_time 置老。
- 补扫轮：全 SUCCESS → 推 **FINISHED**。
- 幂等核对：补扫前后该单 outbox 始终 **1 条** SENT（id18），流水始终 **1 行**，未重复扣减、未重复发事件。

---

## 7. Redis 锁回归（必测项 6）

### 7.1 20 真并发扣减同一维度

- 工具：`ConcurrentRunner.exe`（20 个 `Task.Run`，每请求独立 `HttpClient`，真并发；非串行 for 循环）。维度 `DC01 / DC01-P-01 / SKU-001 / B-R3-LOCK`，对 8082 主实例（默认锁参数，未覆盖）。
- 取证：`R3-06-lock-raw.txt`、`R3-06-lock-summary.json`。

| 指标 | 值 |
|---|---|
| 并发数 | 20 |
| 墙钟 | 683 ms |
| HTTP 200 | 20 |
| HTTP 429 | **0** |
| 其他 | 0 |
| 最终台账 | **20.000**（基线 0 + 20，无丢失更新） |
| 库存流水 | 20 行 |
| 发件箱 | 20 行 |
| Kafka `wms-stock-event` | 20 条（`B-R3-LOCK` 计数 20） |

并发响应中台账交错可见（11 / 13 / 7 …），最终收敛到 20.000，无丢失更新；429=0（事件已不在锁临界区）。

### 7.2 边界①：写请求 400 后锁已释放

维度 `B-R3-LOCK2`：基线正常请求 10.9 ms；changeQty=0 返回 body.code=400「变动数量不能为 0」（5.9 ms）；紧接着的正常请求 13.3 ms，接近基线，无挂起。取证 `lock2-base-resp.json`、`lock2-bad-resp.json`、`lock2-after-resp.json`。
（说明：计时函数对同一 payload 发了两次请求，第二次命中 requestId 幂等，台账仍正确收敛为 2.000。）

### 7.3 边界②：锁竞争超时 429 有界等待

- 用 redis-cli 手动占用锁 key `wms:stock:DC01:DC01-P-01:SKU-001:B-R3-LOCK3`（token `manual-holder-token`，PX 30000 NX）。
- 调 change-stock：body.code=**429**，文案「该库存维度正在被其他任务变更，请稍后重试: ...」；耗时 **2018 ms**（有界，受 wait-timeout=2000ms 约束，未无限等待）；结束后 DEL 释放（DEL=1）。取证 `lock3-resp.json`。

### 7.4 边界③：Redis 不可用不静默降级本地锁

- 临时 inventory 实例 18082，命令行覆盖 `--spring.data.redis.port=6399`（无服务）、`--eureka.client.enabled=false`；`allow-local-fallback=false`（默认，未改）。
- 启动自检 ERROR：`Redis 不可用，加锁操作将快速失败(503)，原因=Unable to connect to Redis`。
- 写请求：body.code=**503**，「Redis 锁服务不可用，已拒绝本次库存变更: Unable to connect to Redis」，耗时 **257 ms**（快速失败，未回退本地锁）。取证 `inventory-18082.out.log`、`lock4-resp.json`。
- 未停用户的 Redis；该临时实例测完已停。

---

## 8. Kafka 不可用事件不丢（必测项 7）

- 临时 inventory 实例 18082：`--spring.kafka.bootstrap-servers=127.0.0.1:9099`（无服务）、`--eureka.client.enabled=false`，Redis 仍指向健康 6379。
- 经该实例写 3 笔库存（SKU-002 / DC01-NG-01，requestId `R3-KF1-REQ-1/2/3`）：**业务全部 200，429=0**（747 / 100 / 85 ms）。
- 临时实例 producer 持续报 `Connection to node -1 (/127.0.0.1:9099) could not be established. Broker may not be available`（`inventory-18082-kafka.out.log`）。
- **隔离限制（如实记录，未美化）**：临时实例与主实例 8082 共用同一 MySQL 与同一 `wms_event_outbox` 表，主实例 OutboxRelay 指向健康 9092，把这 3 条发件箱**抢投兜底**成功；本轮**未观测到长期停留的 PENDING/FAILED 样本**（查询两库 outbox 均无 `status<>'SENT'` 行）。id93 `retry_count=1` 可佐证首次投递曾失败、后被重试成功；id94/95 retry_count=0。
- 最终 3 条事件均到达 `wms-stock-event`（businessNo `R3-KF1-BIZ-1/2/3`，`R3-07-kafka-stock-after.txt`），条数与业务流水一致（3），未丢事件。
- 结论：业务路径在 broker 不可用时不阻断（200/429=0）；事件经发件箱 + relay 重试最终投递成功。**严格隔离的长期 PENDING/FAILED 观测受同库主实例抢投兜底限制，本轮未复现**（与第一轮 K2 结论一致）。

---

## 9. 幂等（必测项 8）

| 场景 | 操作 | 结果 |
|---|---|---|
| 同一单据重复 confirm | 对 FINISHED 的 `OUT1794977962652` 再 confirm | body.code=400「当前状态不允许出库确认: FINISHED」；流水仍 2 行、任务仍 2 行，无二次扣减 |
| 同一库存 requestId 重放 | 重放 `R3-SEED-A1` | 命中幂等直接返回当前库存；流水仍 1 行，台账 B-R3-A1 仍 3.000，未重复扣减/发事件 |
| 同一 OMS 单号重复推送 | 见第 10 节 | 409，不重复建单 |

取证：`t8-confirm-replay-resp.json`、`t8-replay-seed1-resp.json`。

---

## 10. OMS / CTM 复核（必测项 9）

### 10.1 OMS 销售出库 `POST /integration/oms/outbound/order`

- 首次推送 `OMS-R3-001`（DC01，SKU-001 / DC01-A-01-01 / B-R3-OMS1 / qty2）→ **200**，data=`OUT1794978758271`。
- 重复推送同一单号 → body.code=**409**：
  ```
  单据已接收，请勿重复推送: OMS-R3-001
  ```
- 主数据不存在（SKU-NOEXIST）→ body.code=**400**：`商品不存在: SKU-NOEXIST`。
- 拣货齐全后 confirm → 立即 **FINISHED**，outbox 1 条 SENT。
- PICKED 取消经 OMS 端点 `POST /integration/oms/outbound/cancel`（wmsOutboundNo=`OUT1794978157844`）→ body.code=**409**，文案原样透传：
  ```
  已拣货不可直接取消，请走反向入库（退货/调拨退回）回补库存
  ```
  未归一成 502。

### 10.2 CTM 补货 `POST /integration/ctm/replenish/order`

- 首次 `CTM-R3-002`（REPLENISH，SKU-002 qty1）→ **200**，data=`OUT1794978801912`。
- 重复推送 → body.code=**409**：`单据已接收，请勿重复推送: CTM-R3-002`。
- `POST /integration/ctm/cancel` 空 wmsOutboundNo → body.code=**400**：`wmsOutboundNo 不能为空`。

取证：`t9-oms-order-resp1/2.json`、`t9-oms-bad-resp.json`、`t9-oms-cancel-resp.json`、`t9-ctm-order-resp1/2.json`、`t9-ctm-cancel-resp.json`。

---

## 11. 收尾状态（必测项 10）

### 11.1 进程与端口

- 已停掉本轮全部自启 Java：eureka 31472、admin 36544、inventory 23600、inbound 26288、outbound 14424、transfer 7700、stocktake 32424、return 25236、report 33368、integration 29708；临时实例 18082（PID 16808、27820）先后已停。
- 停服后 `netstat`：8761、8081–8089、18082、18084、18089 **全部释放**；`wms-backend` 命令行 Java 进程 0 个。
- 保留运行：Kafka 9092（PID 29640）、MySQL 3306/33060（PID 19848）、Redis 6379（PID 28328）。

### 11.2 清理前后行数快照

取证：`R3-10-cleanup-before.txt`、`R3-10-cleanup-after.txt`、`R3-10-cleanup.sql`。

| 表 | 清理前 | 清理后 |
|---|---|---|
| inventory.wms_inventory | 14 | **1**（种子 WMS-T/LOC-T/SKU-T-001/B20261118/3.000） |
| inventory.wms_inventory_log | 42 | **1**（种子 id80） |
| inventory.wms_event_outbox | 42 | **1**（种子 id3） |
| outbound.wms_outbound_order | 10 | 0 |
| outbound.wms_outbound_item | 11 | 0 |
| outbound.wms_outbound_stock_task | 8 | 0 |
| outbound.wms_event_outbox | 9 | 0 |
| transfer.wms_transfer_order | 1 | 0 |
| integration.wms_external_order | 3 | 0 |
| inbound.wms_inbound_order / item | 0 / 0 | 0 / 0 |
| stocktake.wms_stocktake_order | 0 | 0 |
| return.wms_return_order | 0 | 0 |

- 种子保留核对：库存 `WMS-T/LOC-T/SKU-T-001/B20261118/3.000`；流水 id80（biz_key `IN:IN1794971583777:SKU-T-001:LOC-T:B20261118`）；发件箱 id3（`WMS-T:SKU-T-001`）。
- Flyway 历史各库保留（inventory 2 / outbound 3 / inbound 2 / transfer 2 / stocktake 1 / return 1 / report 1 / integration 2 / admin 2）；admin 种子 2 仓 / 5 库位 / 2 SKU 未动。
- Redis `wms:stock:*` 锁 key 已清空。

---

## 12. 观察到的缺陷 / 现状（未修改，原样记录）

1. **空批次（batchNo 为空串）维度匹配缺口**：SKU-002（不批次管理）三笔 +1 各建一行库存（`wms_inventory` 三条 `batch_no=''`，qty 各 1.000），而不是合并到同一行。`lockInventory` 对空批次走 `isNull(batchNo)`，但写入落库的是空串 `''`，二者不匹配，导致重复建行。位置：`InventoryServiceImpl.lockInventory`（153-165）、`writeLog`/insert（71-83）。
2. **CTM 补货单明细不带库位**：经 `/integration/ctm/replenish/order` 建出的 `OUT1794978238504`，item `location_code=NULL`；`requireItemDim` 按 `locationCode` 等值匹配，PDA 按库位拣货命中不到（404 出库明细不存在）。该单本轮未拣货，已在收尾清理。
3. **外部单据回填 outboundNo 缺失**：external_order id7（CTM-R3-T4）`outbound_no=NULL`，但对应 CTM 建出的出库单 `OUT1794978238504` 已存在；回填链路未对该单落库。
4. **调拨收货不做调入仓入库**：`receive` 仅 IN_TRANSIT→FINISHED，DC02 无库存增加（现状，与实体注释一致，非本轮回归）。
5. **Kafka 不可用的长期 PENDING/FAILED 未能严格隔离观测**：同库主实例 relay 抢投兜底（见第 8 节）。

---

## 13. 本轮未验证项

- **跨实例并发认领 / 租约回收 / 重试上限截断**（双 outbound 实例 8084+18084、过期 PROCESSING 回收、max-retry=5 认领 0 行）：本轮必测清单未列入，未复跑；第一轮已有取证，本轮未新增证据。
- **真实强杀进程的崩溃窗口**（confirm 途中 `Stop-Process -Force` 抓 PENDING 未落库时点）：本轮以单据补扫（第 6 节）覆盖卡住恢复路径，未做真实 kill -9 时序抓取。
- 严格隔离（独立库/独立 Redis/Kafka）下 broker 不可用的长期 PENDING/FAILED 与 last_error 落库样本：受共享主实例抢投兜底限制，本轮未取得。
- 盘点（stocktake）、退货（return）、报表（report）、入库（inbound）业务链路：本轮无业务数据，仅验证启动注册与 Redis 就绪。
