# 第二轮修复与回归报告（出库确认首次认领 / 判定语义）

日期：2026-11-18　模块：`wms-outbound-service`（改动仅限本模块）
环境：MySQL `jim_wms_outbound` / `jim_wms_inventory`、Kafka 9092、Redis 6379 在线；eureka 8761、inventory 8082、outbound 8084，并发第二实例 18084。

## 1. 根因与修法

### 1.1 缺陷现象（OMS 全链路实测）
建单 OUT1794975435867（2 明细）拣货齐全后 confirm 返回 200，但紧接着查库单据 `CONFIRM_FAILED`、任务仍 `PENDING`；30s 扫描周期后才被认领扣减 → FINISHED。健康链路每次确认都先落一个误报的失败态。

### 1.2 根因
- `insertPendingTask` 按规格把 `next_retry_time = now + pending-threshold-ms(10000)`（`OutboundServiceImpl.java:697`），插入后 10s 内不到扫描时点。
- confirm 同步循环走扫描器路径 `dispatchTask → claimNew`，其 SQL 带 `next_retry_time <= now` 条件（`OutboundStockTaskMapper.java:31-35`），刚插入的 PENDING 必然 0 行。
- 旧 `dispatchTask` 把所有结果都返回 1，`allSuccess` 判据失真：未抢到也被当作失败 → `markOrderConfirmFailed` 置 CONFIRM_FAILED。

### 1.3 修法（未放宽任何既有约束）
1. **插入规则原样保留**：`next_retry_time = create_time + pending-threshold-ms(10000)` 未改为 now（`OutboundServiceImpl.java:697,700-701`）。
2. **新增首次专用认领 `claimFirstAttempt`**（`OutboundStockTaskMapper.java:47-54`）：`id=? AND status='PENDING' AND retry_count=0`，**不带** `next_retry_time` 判定；仅 confirm 同步循环使用，扫描器仍走 `claimNew`/`claimExpired`。仍为单条条件 UPDATE、影响 1 行才算抢到、认领时 `retry_count+1`、写 owner/lease_until、独立事务（REQUIRES_NEW）提交，Feign 在事务外。
3. **dispatch 三态**（`OutboundServiceImpl.java:60-65`）：`DISPATCH_NOT_CLAIMED=0` / `DISPATCH_SUCCESS=1` / `DISPATCH_REMOTE_FAILED=2`。
   - 扫描器 `dispatchTask`（390-418）：`claimNew → claimExpired`。
   - 首次 `dispatchFirstAttempt`（424-446）：`claimFirstAttempt`。
   - 公共 `executeClaimed`（451-473）：复核 owner → 续租 → Feign 扣减 → 终态回写，返回三态。
4. **confirm 结束重查全部任务再判定**（171-184）：全 SUCCESS → `finalizeIfAllSuccess`；存在 FAILED → `markOrderConfirmFailed`；仅 PENDING/PROCESSING 在途或被别实例抢走 → **保持 CONFIRMING**，不置失败。
5. **终态幂等守卫**（`finalizeIfAllSuccess` 543-548）：单据已在 FINISHED/PICKED 时直接返回，不再重复写单据、发件箱事件与回调（顺带消除终态事件重复投递）。

既有约束未放宽：PICKED 409 文案、retry 上限 5 截断、租约 60s、扫描 30s、补扫 120s、requestId 规则、发件箱事务语义均照旧。

## 2. 改动文件与行号（本轮）

| 文件 | 动作 | 行号 | 说明 |
|---|---|---|---|
| `mapper/OutboundStockTaskMapper.java` | 修改 | 47-54 | 新增 `claimFirstAttempt`（PENDING + retry_count=0，不判到期） |
| `service/impl/OutboundServiceImpl.java` | 修改 | 60-65 | 三态结果常量 |
| 同上 | 修改 | 161-184 | confirm 循环改 `dispatchFirstAttempt`；结束重查三分支判定 |
| 同上 | 修改 | 304-306 | 扫描器计数口径：`result != DISPATCH_NOT_CLAIMED` 才算认领 |
| 同上 | 修改 | 390-418 | 扫描器 `dispatchTask`（claimNew/claimExpired） |
| 同上 | 新增 | 424-446 | `dispatchFirstAttempt` |
| 同上 | 新增 | 451-473 | 公共 `executeClaimed` 三态返回 |
| 同上 | 新增 | 475-481 | `requireOrderOrNull` |
| 同上 | 修改 | 543-548 | `finalizeIfAllSuccess` 终态幂等守卫 |

未改：wms-common、其它 8 个服务、V1 迁移、Redis 锁、topic 名与 key 规则、库存侧幂等语义。

## 3. 关键 SQL 原文

### 3.1 首次认领 `claimFirstAttempt`（Mapper 47-54，confirm 专用）
```sql
UPDATE wms_outbound_stock_task
SET status='PROCESSING', owner=#{owner}, lease_until=#{leaseUntil},
    retry_count = retry_count + 1, update_time=#{now}
WHERE id=#{id} AND status='PENDING' AND retry_count=0
```

### 3.2 扫描器认领 `claimNew`（Mapper 31-40）
```sql
UPDATE wms_outbound_stock_task
SET status='PROCESSING', owner=#{owner}, lease_until=#{leaseUntil},
    retry_count = retry_count + 1, update_time=#{now}
WHERE id=#{id} AND status IN ('PENDING','FAILED') AND retry_count < #{maxRetry}
  AND (next_retry_time IS NULL OR next_retry_time <= #{now})
```

### 3.3 租约过期回收 `claimExpired`（Mapper 57-66）
```sql
UPDATE wms_outbound_stock_task
SET status='PROCESSING', owner=#{owner}, lease_until=#{leaseUntil},
    retry_count = retry_count + 1, update_time=#{now}
WHERE id=#{id} AND status='PROCESSING' AND lease_until < #{now}
  AND retry_count < #{maxRetry}
```

### 3.4 续租 `renewLease`（Mapper 67-76，仅 owner 匹配）
```sql
UPDATE wms_outbound_stock_task
SET lease_until=#{leaseUntil}, update_time=#{now}
WHERE id=#{id} AND owner=#{owner}
```

### 3.5 终态回写
SUCCESS（Mapper 77-88）：
```sql
UPDATE wms_outbound_stock_task
SET status='SUCCESS', owner=NULL, lease_until=NULL, last_error=NULL,
    next_retry_time=NULL, update_time=#{now}
WHERE id=#{id}
```
FAILED（Mapper 89-99）：清 owner/lease，写 last_error、retry_count、next_retry_time。
CANCELLED（Mapper 100-109）：清 owner/lease。

### 3.6 候选查询 `selectCandidates`（Mapper 20-26）
```sql
SELECT * FROM wms_outbound_stock_task
WHERE (status IN ('PENDING','FAILED') AND retry_count < #{maxRetry}
       AND (next_retry_time IS NULL OR next_retry_time <= #{now}))
   OR (status = 'PROCESSING' AND lease_until IS NOT NULL AND lease_until < #{now})
ORDER BY id ASC
```

## 4. 真机回归证据

服务：eureka 31280 / inventory 36488 / outbound8084 1712 / outbound18084 1972，均默认配置注册 8761。
证据目录：`D:\wms\wms-backend\.sessions-evidence\round2\`。

### a) 健康链路（建单 → 拣货齐全 → confirm，同请求返回后立刻查库）
单据 `OUT1794976876513`，2 明细：SKU-T-001/LOC-T/B20261118 1；SKU-T-002/LOC-T2/B20261118 2。

- confirm 响应（`a-confirm-resp.txt`）：`{"code":200,"msg":"ok","data":null}`，**TIME_TOTAL=2.303098s**。
- 查库（`a-after-confirm.txt`）：
  - 单据：`FINISHED`（无 CONFIRM_FAILED 中间态）。
  - 任务 23/24：均 `SUCCESS`，`retry_count=1`，owner/lease_until/last_error/next_retry_time 全 NULL。
  - 库存流水：130（-1，3→2）、131（-2，5→3），biz_key 各对应 requestId，**各 1 行**。
  - requestId：`OUT:OUT1794976876513:SKU-T-001:LOC-T:B20261118`、`OUT:OUT1794976876513:SKU-T-002:LOC-T2:B20261118`。
- 日志（`a-log.txt`）：confirm 置 CONFIRMING → 两条 PENDING 插入 → 首次认领扣减 → FINISHED；发件箱 `wms-order-status` 投递成功。
- 备注：单据 `last_error` 记录 `notifyOmsStatus 异常: RetryableException: wms-integration-service .../oms/status`，因 integration 服务本轮未启动；按设计 OMS 回调失败只记 WARN + 单据级 last_error，**不阻断 FINISHED**。

### b) 远程失败链路 → 扫描补偿（幂等）
单据 `OUT1794977170371`，1 明细 SKU-T-001/LOC-T/B20261118 1。先停 inventory 8082 后 confirm。

- confirm 响应（`b-confirm-resp.txt`）：`{"code":200,...}`，**TIME_TOTAL=2.232064s HTTP=200**，未抛 500。
- 失败后查库（`b-after-failure.txt`）：
  - 单据 `CONFIRM_FAILED`；任务 25 `FAILED`，`retry_count=1`，owner/lease NULL，`last_error=Feign 调用异常: RetryableException: Connection refused: getsockopt executing POST http://wms-inventory-service/internal/inventory/change-stock`，`next_retry_time=2026-11-18 12:48:10`；库存流水 **0 行**。
- 重启 inventory；为缩短等待将该 FAILED 任务 `next_retry_time` 前置到当前时间（扫描补偿语义不变，非改代码规则）。
- 补偿后查库（`b-after-compensate.txt`）：
  - 单据 `FINISHED`；任务 25 `SUCCESS`，`retry_count=2`，owner/lease/last_error/next_retry_time 全 NULL。
  - 库存流水仅 **1 行**（id 132，-1，2→1，biz_key 同 requestId），幂等无重复扣减。
- 日志（`b-log.txt`）：12:47:10 置 FAILED + CONFIRM_FAILED；12:48:15 调度线程扫描认领 → SUCCESS → FINISHED；发件箱仅 id 11 一条。

### c) 并发确认（8084 + 18084 同库同时 confirm 同一单）
单据 `OUT1794977369142`，1 明细 SKU-T-002/LOC-T2/B20261118 1。

- 响应（`c-confirm-8084.txt` / `c-confirm-18084.txt`）：
  - 8084：`200`，TIME=1.426431s。
  - 18084：`400 当前状态不允许出库确认: CONFIRMING`（在状态校验处被拦，未进入 dispatch，未置 CONFIRM_FAILED）。
- 查库（`c-after.txt`）：单据 `FINISHED`；任务 26 `SUCCESS`，`retry_count=1`；库存流水仅 **1 行**（id 133，-1，3→2，biz_key 唯一），无重复扣减。
- 18084 日志仅一条 `selectList` 后返回 400，无任务变更。

### d) 旧 a~e 回归（SQL 复刻 Mapper 原始 UPDATE，逐条 ROW_COUNT）
脚本 `d-regression.sql`，结果 `d-regression-result.txt`：

| 项 | 结果 | 证据 |
|---|---|---|
| d1 认领唯一性 | 首次 **1** 行，第二次 **0** 行；retry_count 0→1 | `d1_first_claim_rows=1`，`d1_second_claim_rows=0`，任务 27 PROCESSING retry=1 |
| d2 retry 自增 + 5 次截断 | retry=5 时再认领 **0** 行 | `d2_at_max_retry_claim_rows=0`，任务 27 FAILED retry=5 |
| d3 租约过期回收 | 过期 **1** 行（owner 变更、retry 1→2）；未过期 **0** 行 | `d3_expired_reclaim_rows=1`，`d3_healthy_not_stolen_rows=0`；28 归 reg-owner-2 retry=2，29 仍 old-owner retry=1 |
| d4 SUCCESS 终态清空 | owner/lease_until/last_error/next_retry_time 全 NULL | 任务 28 SUCCESS，四列 NULL |
| d5 PENDING 时间差仍 10000ms | 源码 `nextRetryTime = now.plusNanos(pendingThresholdMs*1e6)`；日志插入参数 `next_retry_time=12:47:17.965269400`、`create_time=12:47:07.965269400`，差值 **10.000000000s = 10000ms** | `b-log.txt` 任务 insert 行；`OutboundServiceImpl.java:697,700-701` |

## 5. 构建结论

- `.sessions-outbound-build3.log`：`BUILD SUCCESS`（首次认领 + 三态判定修复）。
- `.sessions-outbound-build4.log`：`BUILD SUCCESS`（终态幂等守卫）。
- 命令：`mvn -B -DskipTests -pl wms-outbound-service package`（未加 -am、未全量 clean）。

## 6. 收尾状态

- 已停本轮启动的全部 Java：eureka 8761、inventory 8082、outbound 8084、outbound 18084；复核 8761/8081-8089/18084 全部 free。
- 保留：Kafka PID 29640；未触碰 IDE Java LSP。
- 数据清理（`cleanup-before.txt` → `cleanup-after.txt`，脚本 `cleanup.sql`）：
  - outbound：order/item/task/outbox 全部清零；Flyway 历史 V1/V2/V3 保留 success。
  - inventory：删除本轮出库流水 130/131/132/133 与库存事件发件箱 53-56；删除本轮新增库存行 16；种子行 10（SKU-T-001）数量恢复 3.000；种子流水 80、种子发件箱 3 保留。

## 7. 仍未覆盖 / 遗留

- 完整 Eureka 多实例注册发现与 Feign 负载均衡长稳链路（本轮仅两实例短时并发）。
- 与 transfer 服务真实联调的 404 报文分支、`/receive` 回补链路；TRANSFER_OUT 在途通知本轮未走真实 transfer。
- 高并发压测下的认领竞争、重复扣减边界（本轮并发仅一次双实例 confirm）。
- `lease-renew-interval-ms` 仍为预留配置：当前 Feign 前后各续租一次，未做周期续租；SUCCESS 终态回写清空 owner 后，Feign 后的第二次续租会 0 行并打一条 WARN（ benign，不影响终态）。
- integration 服务（8089）本轮未启动，OMS/CTM 回调走离线兜底 WARN 分支，未验证回调成功报文。
- Redis 锁残留未巡检（本机无 redis-cli）；锁 30s TTL，服务已停，无活动持锁。
