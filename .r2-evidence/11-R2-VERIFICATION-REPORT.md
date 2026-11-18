# WMS 后端本轮改造 · 第一轮真机验证报告（R2）

- 验证时间：2026-11-18 11:34 ~ 12:01（Asia/Shanghai）
- 项目根：`D:\wms\wms-backend`
- 证据目录：`D:\wms\wms-backend\.r2-evidence\`
- 验证口径：只验证与取证，未改任何代码 / yml / 迁移 / 文档；缺陷仅记录。
- 中间件全程保持运行：Kafka 4.4.0 KRaft `127.0.0.1:9092`（PID 29640）、MySQL 3306（PID 19848）、Redis 6379（PID 28328）。

---

## 1. 全量构建复核

- 命令：`mvn -B -DskipTests clean package`（在 `D:\wms\wms-backend`）
- 日志：`.r2-evidence\01-build-r2.log`（用户前序只读日志 `.build-full-r2.log` 未覆盖）
- 结果：**BUILD SUCCESS，12/12 reactor SUCCESS，`[ERROR]` 计数 = 0**
- Total time：**33.309 s**；Finished at `2026-11-18T11:34:55+08:00`
- 各模块：wms-backend / wms-common / wms-eureka-server / wms-admin / wms-inventory / wms-inbound / wms-outbound / wms-transfer / wms-stocktake / wms-return / wms-report / wms-integration 全部 SUCCESS。

> 取证附注（非代码缺陷，打包观察）：全量构建后首次启动时发现 `wms-outbound-service`、`wms-integration-service` 两个 jar 为瘦包（44024 / 31061 字节，无主清单属性），其余服务为 67–83 MB fat jar。停掉占用 jar 的运行进程后，`mvn -B -DskipTests -pl wms-outbound-service,wms-integration-service -am package` 重打包成功（日志 `09-repack-outbound-integration.log`，BUILD SUCCESS 4.441 s），两 jar 恢复 83,293,511 / 67,439,710 字节。初次 repackage 失败原因为运行中 jar 被进程锁定无法 rename（`Unable to rename ...jar to ...jar.original`），属 Windows 文件锁，非构建脚本错误。

---

## 2. 全栈启动注册

启动顺序：eureka(8761) → admin(8081) → inventory(8082) → inbound(8083) → outbound(8084) → transfer(8085) → stocktake(8086) → return(8087) → report(8088) → integration(8089)。全部默认配置，**未覆盖** wait-timeout / scan-interval / lease-seconds 等任何参数。

### 2.1 Eureka 注册清单（原始 JSON：`02-eureka-apps.json`；CSV：`02-eureka-apps.csv`）

`apps__hashcode = UP_9_`，9 个应用全部 UP：

| App | Instance | Port | Status |
|---|---|---|---|
| WMS-ADMIN-SERVICE | wms-admin-service:8081 | 8081 | UP |
| WMS-INVENTORY-SERVICE | wms-inventory-service:8082 | 8082 | UP |
| WMS-INBOUND-SERVICE | wms-inbound-service:8083 | 8083 | UP |
| WMS-OUTBOUND-SERVICE | wms-outbound-service:8084 | 8084 | UP |
| WMS-TRANSFER-SERVICE | wms-transfer-service:8085 | 8085 | UP |
| WMS-STOCKTAKE-SERVICE | wms-stocktake-service:8086 | 8086 | UP |
| WMS-RETURN-SERVICE | wms-return-service:8087 | 8087 | UP |
| WMS-REPORT-SERVICE | wms-report-service:8088 | 8088 | UP |
| WMS-INTEGRATION-SERVICE | wms-integration-service:8089 | 8089 | UP |

9 个业务服务端口均 LISTENING；eureka 8761 LISTENING。

### 2.2 Redis 锁通道就绪

除 eureka 外 9 个业务服务启动日志均出现：
`Redis 锁通道就绪（127.0.0.1:6379:0），PING=PONG`（逐服务在 `logs\<name>.out.log` 命中，admin/inventory/inbound/outbound/transfer/stocktake/return/report/integration 全部 READY）。

### 2.3 发件箱装配

inventory / inbound / outbound 三服务日志中**未出现**「发件箱已启用但容器内没有 KafkaTemplate，本轮不投递」类 WARN；`wms.outbox.enabled=true` 条件装配生效，OutboxRelay 正常扫描投递（inventory 日志可见 `发件箱投递成功 ... 发件箱本轮投递处理 N 条`）。

---

## 3. Kafka 真实投递核对（三处生产者）

### 3.1 库存事件 `wms-stock-event`（6 分区）

经 `/internal/inventory/change-stock` 造 2 笔（requestId `R2-STOCK-001/002`，businessNo `INR2-001/002`，SKU-001 / B-R2-001，+2、+3）。

**流水（`jim_wms_inventory.wms_inventory_log`）：**

| id | businessNo | changeQty | beforeQty | afterQty | biz_key |
|---|---|---|---|---|---|
| 81 | INR2-001 | +2.000 | 0.000 | 2.000 | R2-STOCK-001 |
| 82 | INR2-002 | +3.000 | 2.000 | 5.000 | R2-STOCK-002 |

**outbox（`jim_wms_inventory.wms_event_outbox`）：** 对应 2 行，状态 **SENT**。

**Kafka 消费（`03-kafka-stock-beginning.txt`，含历史共 5 条）**，本轮两条原文：

```
DC01:SKU-001|{"eventId":"...","eventType":"INBOUND","warehouseCode":"DC01","locationCode":"DC01-P-01","skuCode":"SKU-001","batchNo":"B-R2-001","businessNo":"INR2-001","changeQty":2,"beforeQty":0,"afterQty":2,...}
DC01:SKU-001|{"eventId":"...","eventType":"INBOUND","warehouseCode":"DC01","locationCode":"DC01-P-01","skuCode":"SKU-001","batchNo":"B-R2-001","businessNo":"INR2-002","changeQty":3,"beforeQty":2.000,"afterQty":5.000,...}
```

字段对照：`businessNo` 与流水 business_no 一致；`changeQty / beforeQty / afterQty` 与流水 change_qty / before_qty / after_qty 逐条一致。topic 名未变（`wms-stock-event`）。

### 3.2 单据状态事件 `wms-order-status`（3 分区）

出库单 `OUT1794973273559`（sourceNo `CTM-R2-001`，SKU-001/B-R2-001，plan/pick 2）确认；首轮主流程未抢到任务（claimNew/claimExpired 均 Updates:0），整单置 CONFIRM_FAILED；等待 PENDING 阈值后定时补偿把任务 id=17 推到 SUCCESS，单据 FINISHED，outbox 写 `wms-order-status`，event_key = `OUT1794973273559`，payload：
`{"orderNo":"OUT1794973273559","message":"出库完成","status":"FINISHED"}`。

**Kafka 消费（`04-kafka-order-status.txt`，含历史共 4 条，`--property print.key=true`，分隔符 `|`）：**

```
OUT1794973273559|{"orderNo":"OUT1794973273559","message":"出库完成","status":"FINISHED"}
```

- **Key 证明**：消息 key = `OUT1794973273559`，与 `orderNo` 完全相等（key 规则未变）。
- 字段 `orderNo / status / message` 齐全；topic 名未变（`wms-order-status`）。

### 3.3 结论

- 两处 topic 名未变；库存事件 key 规则（`warehouse:sku`）未变；单据状态 key = orderNo 未变。
- 条数与流水一致：库存 2 笔 → outbox 2 SENT → topic 2 条；单据 1 笔 → outbox 1 SENT → topic 1 条（本轮）。
- 入库路径本轮未单独造入库完成单（库存两笔走 internal change-stock）；入库 outbox 表本轮 0 行（见收尾快照）。

---

## 4. 并发对比（核心）

并发工具：编译后的 .NET 控制台 `ConcurrentRunner.exe`（源码 `ConcurrentRunner.cs`），内部 `Task.Run` 起 20 个任务、每请求独立 `HttpClient`，真正 20 并发（非串行 for）；PS 5.1 内 `Task.Run`/闭包并发存在 runspace/重载歧义，已弃用。

### 4.1 场景 K1「Kafka 在线」

- 目标：inventory 8082（默认配置，wait-timeout=2000ms，未做任何参数覆盖）
- 维度：DC01 / DC01-P-01 / SKU-001 / **B-R2-K1**，20 并发各 +1
- 汇总：`05-K1-summary.json`；逐请求：`05-K1-raw.txt` / `05-K1-results.csv`

| 指标 | 实测 |
|---|---|
| 并发数 / 方式 | 20 / .NET HttpClient Task 真并发 |
| HTTP 200 | **20** |
| HTTP 429 | **0** |
| 其他码 | 0 |
| 墙钟耗时 | **1079 ms** |
| 最终台账（B-R2-K1） | **20.000**（基线 0 + 20.000） |
| 流水新增 | **20 行**（K1-REQ-001..020） |
| outbox 新增 | **20 行，全部 SENT** |
| Kafka 消费条数 | **20**（`06-kafka-stock-after-K1.txt`，K1-BIZ 命中 20） |

**期望 429 = 0 达成**：事件已移出锁临界区，在线 Kafka 下无锁等待限流。

### 4.2 场景 K2「Kafka 不可用」

- **未停用户 Kafka**。另起 inventory 临时实例 **18082**，命令行覆盖：
  `--spring.kafka.bootstrap-servers=127.0.0.1:9099`（9099 无服务）、`--eureka.client.enabled=false`、`--server.port=18082`。
- 维度：DC01 / DC01-P-01 / SKU-001 / **B-R2-K2**，20 并发各 +1（默认锁参数）。
- 汇总：`07-K2-summary.json`；逐请求：`07-K2-raw.txt`。

| 指标 | 实测 |
|---|---|
| 并发数 / 方式 | 20 / .NET HttpClient Task 真并发 |
| HTTP 200 | **20** |
| HTTP 429 | **0** |
| 其他码 | 0 |
| 墙钟耗时 | **1704 ms** |
| 最终台账（B-R2-K2） | **20.000** |
| 流水新增 | **20 行**（K2-REQ-001..020） |
| outbox | 20 行，最终全部 SENT（见下方偏差说明） |
| Kafka 消费条数 | **20**（`08-kafka-stock-after-K2.txt`，K2-BIZ 命中 20，文件末尾 Processed 46 messages） |

**业务侧 429 = 0 达成**：broker 不可用未把库存变更挡在锁临界区。

**必须如实记录的偏差（模拟口径与同库兜底）：**
1. 本场景是用不可达 bootstrap `127.0.0.1:9099` 模拟 broker 不可用，**不是真的停掉用户的 Kafka**。
2. 18082 与 8082 共用同一 MySQL / Redis，两实例 OutboxRelay 同时扫描同一 outbox 表：K2 的 20 条实际由 8082（连真实 9092）在约 1 秒内抢投 19 条 SENT；18082 对 **id=37** 投递失败 1 次（`TimeoutException: Topic wms-stock-event not present in metadata after 1000 ms`，retry=1，日志 `inventory-18082.out.log` 11:47:09.793 `发件箱投递失败 id=37, retry=1, lastError=Send failed`），随后 8082 在 **11:47:15.886** 追平该条 SENT（`inventory.out.log` `发件箱投递成功 id=37`）。
3. 因此本轮**未观察到 K2 批次长期停留 PENDING/FAILED 的样本**（主实例同库兜底投递）；outbox 表在清理前快照中 K2 20 行最终均为 SENT。id=37 是唯一一次可取证的投递失败 → 回补记录。

### 4.3 积压回补

- 停掉 18082 临时实例（PID 25780 已终止）。
- 由连真实 9092 的 8082 主实例把 K2 唯一失败的 id=37 追平为 SENT；从失败（11:47:09.793）到追平（11:47:15.886）约 **6.1 s**。
- 消费 `wms-stock-event` 证明 K2 20 条最终全部到 topic。结论：**账变事件不会因 Kafka 短时不可用而永久丢失**。

### 4.4 改造前 / 改造后对照

| 场景 | 改造前（题面给定口径，本轮未实测复现） | 改造后 K1（Kafka 在线） | 改造后 K2（broker 不可用，9099 模拟） |
|---|---|---|---|
| 并发数 | 20 | 20 | 20 |
| HTTP 200 | 3 | **20** | **20** |
| HTTP 429 | **17** | **0** | **0** |
| 墙钟 | — | 1079 ms | 1704 ms |
| 台账 | — | 基线+20.000 | 20.000 |
| 流水 | — | 20 | 20 |
| outbox | — | 20 SENT | 20 SENT（1 条经回补） |
| Kafka 条数 | — | 20 | 20 |

> 改造前「17×429 / 3×200」为题面给定基线，本轮未复现；改造后两组实测 429 均为 0。

---

## 5. 崩溃窗口（PENDING 已落库、尚未调库存时进程被杀）

### 5.1 尝试记录（真实路径）

造一笔有库存的出库单 `OUT1794974228602`（SKU-001 / B-R2-001，plan/pick 1，台账基线 3.000）：

- `11:58:03.872` 发出 confirm（8084，PID 27424）
- `11:58:03.995` confirm 返回 HTTP 200
- `11:58:04.009` `Stop-Process -Id 27424 -Force` 强杀 8084

杀进程前后库内取证：
- 8084 日志（`outbound.out.log`，11:58:03.962~.978）：confirm 事务已置 CONFIRMING 并 insert PENDING 任务 id=19；主线程 claimNew Updates:0 → claimExpired Updates:0（主流程未抢到，任务仍 PENDING），随后整单置 CONFIRM_FAILED。
- 强杀时点，另一实例 **18084（PID 24632）** 仍在线同库。

### 5.2 重启 / 补偿推进结果

18084 定时扫描在 **11:58:18.455** `claimNew` 抢到 id=19（Updates:1），11:58:18.631 `markSuccessTerminal` Updates:1，单据终态确认 FINISHED；11:58:20.344 OutboxRelay 投递 `wms-order-status` 成功 id=4。

最终库内核验：

| 项 | 结果 |
|---|---|
| 单据 `OUT1794974228602` | **FINISHED**（update_time 11:58:19） |
| 任务 id=19 | SUCCESS，retry_count=1，owner/lease 已清空 |
| 台账 B-R2-001 | 3.000 → **2.000**（仅扣 1） |
| `wms_inventory_log` 该 requestId | **仅 1 行**（id=124，`-1.000`，before 3.000 → after 2.000，biz_key `OUT:OUT1794974228602:SKU-001:DC01-P-01:B-R2-001`） |
| outbox（outbound 库） | event_key `OUT1794974228602`，`wms-order-status`，SENT |

### 5.3 幂等结论

该 requestId 库存流水**只有 1 行**，未重复扣减；强杀后由在线实例补偿推进到终态。

> 取证说明：本轮真实抓到的窗口是「主流程未抢到、任务 PENDING、整单 CONFIRM_FAILED」，进程在主流程返回后被强杀；随后由另一实例扫描补偿成功。未抓到「杀在 Feign 调用半途」的更窄时序；因同库另有在线实例，未采用纯确定性 SQL 构造等价 PENDING 的兜底路径。8084 重启后其日志被重定向截断，11:58 之前的 8084 明细以 18084 在线日志 + 库内终态为准。

---

## 6. 跨实例认领与租约

双 outbound：8084（重启后 PID 25620，首轮认领实例 PID 27424）与 18084（PID 24632），指向同一库。为制造 Feign 必然失败，用无库存 SKU-002 / B-CLAIM 建单 `OUT1794973919315`（id=22，item id=21，pick 3），落任务 **id=18**：change_qty=-3.0000，request_id `OUT:OUT1794973919315:SKU-002:DC01-P-01:B-CLAIM`。

### 6.1 同一轮只被一个实例认领（条件 UPDATE 影响行）

- 首轮：confirm 线程（8084 `nio-8084-exec-5`，11:52:47.570）claimNew Updates:0 → claimExpired Updates:0（未抢到）。
- 11:53:18.033 定时线程（8084 `scheduling-1`）claimNew **Updates:1**（抢到），renewLease Updates:1，扣减失败 markFailedTerminal Updates:1；本轮扫描汇总 `候选=1, 认领=1, 回写=1`。
- 18084 各轮 `selectCandidates` 均 **Total:0**，未参与竞争。
- 任务最终 FAILED，retry_count=1，last_error `code=400, msg=库存不足, skuCode=SKU-002, 现有=0, 需要=3.0000`；单据 CONFIRM_FAILED。
- owner 格式：`wms-outbound-service:<port>:<thread>`（源码 `OutboundServiceImpl.buildOwner()` 709-713 行：app:port:thread）；18084 认领参数可见 `wms-outbound-service:18084:scheduling-1`，端口段可区分实例。

### 6.2 过期租约回收

SQL 把 id=18 置为 `PROCESSING`、owner=`wms-outbound-service:8084:manual`、`lease_until` 已过期 10 秒、retry_count=1。
- 下一轮扫描：8084 `claimExpired` **Updates:1** 回收重新认领（owner 变更、retry_count 1→2），扣减仍失败 markFailedTerminal，任务 FAILED，retry_count=2。
- 18084 同轮 selectCandidates Total:0，未重复认领。
- 证据：库内 retry_count 由 1 递增到 2；回收走 `claimExpired`（PROCESSING + lease_until < now + retry_count < maxRetry）。

### 6.3 未过期租约不被抢

SQL 把 id=18 置为 `PROCESSING`、owner=`wms-outbound-service:18084:manual`、`lease_until` = now+60s（未过期）、retry_count=2。
- 等待两轮 30s 扫描：8084 与 18084 的 selectCandidates 均 **Total:0**；任务保持 PROCESSING / owner / lease_until 不变，未被任何一方抢认领。

### 6.4 上限截断（max-retry=5）

SQL 把 id=18 置为 `PENDING`、`retry_count=5`、next_retry_time=now()、owner/lease 清空。
- 等待两轮扫描：两实例 selectCandidates 均 Total:0；任务保持 PENDING / retry_count=5 / owner=NULL，**claimNew / claimExpired 影响 0 行**，不再被认领（`retry_count < #{maxRetry}` 条件截断，maxRetry=5）。

### 6.5 收尾

临时实例 18084 已在收尾阶段停止（见第 7 节）。

---

## 7. 收尾状态

### 7.1 进程与端口

- 已停止本轮自启的全部 Java：eureka + 9 业务服务 + 临时实例 18084（18082 此前已停）。
- 停止后核查：`8761 / 8081-8089 / 18082 / 18084` **全部释放**（netstat LISTENING 无残留）。
- 剩余 Java 进程仅 **Kafka PID 29640**；未触碰 IDE Java LSP（PID 8272）。
- 中间件保持运行：Kafka 9092（29640）、MySQL 3306/33060（19848）、Redis 6379（28328）。

### 7.2 业务数据清理

清理范围：本轮 R2 冒烟单号 / requestId / 批次（B-R2-001、B-R2-K1、B-R2-K2、B-CLAIM；INR2-001/002；K1/K2-BIZ/REQ；OUT1794973273559、OUT1794973919315、OUT1794974228602；CTM-R2-001 / CTM-R2-CLAIM / CTM-R2-CRASH）对应的 order / item / task / inventory / log / outbox。
**保留**：Flyway `flyway_schema_history` 历史、admin 种子（仓/库位/SKU/面积）、上一轮遗留的 SKU-T-001 种子库存（WMS-T/LOC-T/B20261118，3.000）及其流水 id=80、outbox id=3。

行数快照：清理前 `10-cleanup-before.txt`，清理后 `10-cleanup-after.txt`。

| 表（库） | 清理前 | 清理后 |
|---|---|---|
| wms_outbound_order（outbound） | 3 | 0 |
| wms_outbound_item（outbound） | 3 | 0 |
| wms_outbound_stock_task（outbound） | 3 | 0 |
| wms_event_outbox（outbound） | 2 | 0 |
| wms_inventory（inventory） | 4 | **1**（仅 SKU-T-001 种子） |
| wms_inventory_log（inventory） | 45 | **1**（仅上一轮 id=80） |
| wms_event_outbox（inventory） | 45 | **1**（仅上一轮 id=3） |
| wms_inbound_order / item / outbox（inbound） | 0 / 0 / 0 | 0 / 0 / 0 |
| wms_transfer_order（transfer） | 0 | 0 |
| wms_stocktake_order / item（stocktake） | 0 / 0 | 0 |
| wms_return_order（return） | 0 | 0 |
| wms_external_order（integration） | 0 | 0 |

清理后残留库存：`WMS-T / LOC-T / SKU-T-001 / B20261118 / 3.000`。

---

## 8. 未验证项 / 局限 / 缺陷记录

### 8.1 未验证或未充分验证

1. **K2 长期 PENDING/FAILED 样本未观测到**：18082 与 8082 同库同 Redis，主实例兜底投递，K2 20 条最终全部 SENT；仅 id=37 留下一次失败→回补记录。未在「无在线兜底实例、broker 持续不可用」的隔离条件下观察 outbox 长期滞留与 last_error 内容。
2. **更窄崩溃时序未抓到**：未实测「进程被杀在 Feign 扣减调用半途（已认领未回写）」的窗口；本轮为「主流程未抢到、PENDING 落库后强杀，由另一实例补偿」。未使用纯确定性 SQL 等价构造路径。
3. **入库完成单据状态事件**：本轮库存两笔走 internal change-stock，未造一笔真实入库完成单去核对 inbound → `wms-order-status` 的入库消息体；inbound outbox 本轮 0 行。
4. **未过期租约不被抢**仅做了两轮扫描观察（约 60s 窗口），未压更长时间或并发抢占。
5. **改造前基线（17×429 / 3×200）** 为题面给定口径，本轮未在旧代码上复现。
6. transfer / stocktake / return / report / integration 业务链路本轮未造数（对应表清理前后均为 0），其 outbox / 锁 / 补偿逻辑未做真机验证。

### 8.2 观察到的打包现象（非业务代码缺陷）

- 全量 `clean package` 后 outbound、integration 两模块首次产物为瘦 jar（无主清单属性）；停掉占用进程后单独 repackage 恢复 fat jar。初次 repackage 报 `Unable to rename ...jar to ...jar.original`，系 Windows 运行中文件锁。建议后续在干净环境（无运行中 jar）执行全量打包复核。

### 8.3 证据文件索引

- 构建：`01-build-r2.log`；重打包：`09-repack-outbound-integration.log`
- 注册：`02-eureka-apps.json` / `02-eureka-apps.csv`
- Kafka：`03-kafka-stock-beginning.txt`、`04-kafka-order-status.txt`、`06-kafka-stock-after-K1.txt`、`08-kafka-stock-after-K2.txt`
- 并发：`05-K1-summary.json` / `05-K1-raw.txt` / `05-K1-results.csv`；`07-K2-summary.json` / `07-K2-raw.txt`；工具 `ConcurrentRunner.cs` / `ConcurrentRunner.exe`
- 清理快照：`10-cleanup-before.txt` / `10-cleanup-after.txt`
- 服务日志：`logs\<eureka|admin|inventory|inbound|outbound|transfer|stocktake|return|report|integration|inventory-18082|outbound-18084>.out.log`（`.err.log` 均为 0 字节）
- 请求/响应样本：`payload\*.json`、`resp-*.json`
