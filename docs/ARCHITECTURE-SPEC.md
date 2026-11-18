# WMS 微服务架构规范（ARCHITECTURE-SPEC）

> 本文件是 4 个并行业务 agent 的唯一规范来源。骨架、公共契约、端口/库名/表归属已在本工程落定，业务代码必须无缝接上本骨架，不得更改对外契约与下述既定决策。
>
> 既定决策（不可更改）：① 原地改造，工程根目录 `D:\wms\wms-backend`；② database-per-service，每个服务独立库；③ 服务发现使用 Spring Cloud Netflix Eureka（不使用 Nacos）。

## 1. 技术栈与版本

| 项 | 版本 / 取值 |
|---|---|
| JDK | 17 |
| Spring Boot | 3.2.5（spring-boot-starter-parent） |
| Spring Cloud BOM | 2023.0.2（`spring-cloud-dependencies`，import，不再升级） |
| MyBatis-Plus | 3.5.5（`mybatis-plus-spring-boot3-starter`） |
| Hutool | 5.8.27（`hutool-all`） |
| 数据库 | MySQL 8.0.46，`127.0.0.1:3306`，root/root |
| 迁移工具 | Flyway（`flyway-core` + `flyway-mysql`） |
| 构建 | Maven 3.9.9 |
| 编码 | UTF-8 |

> 端口变化提示：旧单体启动端口为 8081；拆分后 **8081 归 `wms-admin-service`**，旧单体的 8081 不再存在。最终 README 需说明此变化。

## 2. 模块 / 端口 / 库名 / 表归属总表

| 模块（spring.application.name） | 端口 | 数据库 | 归属表 |
|---|---:|---|---|
| `wms-eureka-server` | 8761 | 无业务库 | — |
| `wms-admin-service` | 8081 | `jim_wms_admin` | `wms_warehouse`、`wms_area`、`wms_location`、`wms_sku` |
| `wms-inventory-service` | 8082 | `jim_wms_inventory` | `wms_inventory`、`wms_inventory_log`（唯一允许增量：`biz_key` 列 + 唯一索引） |
| `wms-inbound-service` | 8083 | `jim_wms_inbound` | `wms_inbound_order`、`wms_inbound_item` |
| `wms-outbound-service` | 8084 | `jim_wms_outbound` | `wms_outbound_order`、`wms_outbound_item`、`wms_outbound_stock_task`（新增，补偿用） |
| `wms-transfer-service` | 8085 | `jim_wms_transfer` | `wms_transfer_order` |
| `wms-stocktake-service` | 8086 | `jim_wms_stocktake` | `wms_stocktake_order`、`wms_stocktake_item` |
| `wms-return-service` | 8087 | `jim_wms_return` | `wms_return_order` |
| `wms-report-service` | 8088 | `jim_wms_report` | 无业务表（聚合走 Feign 到 inventory，禁止跨库直连） |
| `wms-integration-service` | 8089 | `jim_wms_integration` | `wms_external_order` |

### 2.1 表结构迁移要点

- 旧单体 V1 共 15 张表，按上表逐表搬到各自服务的 Flyway 脚本；**列名、类型、索引沿用旧结构，不得改表结构**。
- 唯一允许的结构增量：`wms_inventory_log` 增加 `biz_key` 列并建唯一索引（库存幂等用，见第 7 节）。
- 新增补偿表 `wms_outbound_stock_task`（归 outbound 库），建议字段：
  `id`、`outbound_no`、`sku_code`、`batch_no`、`location_code`、`plan_qty`、`request_id`、`status`（`PENDING`/`SUCCESS`/`FAILED`）、`last_error`、`retry_count`、`next_retry_time`、`create_time`、`update_time`。
- 表归属是强约束：任何服务不得在自己的迁移脚本里建、改、删其他服务的表。

## 3. 依赖方向（Feign 调用关系）

### 3.1 谁调谁

```
wms-admin-service        （主数据底座，不依赖其他业务服务）
wms-inventory-service    （库存真值底座，不依赖其他业务服务）

wms-inbound-service      ──Feign──▶ wms-inventory-service（上架扣加库存）
                         ──Feign──▶ wms-admin-service（校验仓/库位/SKU）

wms-outbound-service     ──Feign──▶ wms-inventory-service（出库扣减/冻结）
                         ──Feign──▶ wms-admin-service（校验仓/库位/SKU）
                         ──Feign──▶ wms-integration-service（CTM 状态回调）

wms-transfer-service     ──Feign──▶ wms-inventory-service（TRANSFER_OUT / TRANSFER_IN）
                         ──Feign──▶ wms-admin-service

wms-stocktake-service    ──Feign──▶ wms-inventory-service（STOCKTAKE_ADJ 盘盈盘亏）
                         ──Feign──▶ wms-admin-service

wms-return-service       ──Feign──▶ wms-inventory-service（RETURN_IN 回库）
                         ──Feign──▶ wms-admin-service

wms-report-service        ──Feign──▶ wms-inventory-service（InventoryApi./list 聚合）
                         ──Feign──▶ wms-admin-service（可选，主数据补全）

wms-integration-service  ──Feign──▶ wms-outbound-service（OutboundApi 建单 / 取消）
                         ──Feign──▶ wms-admin-service（OMS 建单前校验仓 / 库位 / SKU）
```

### 3.2 被依赖方（被谁调用）

| 被依赖服务 | 依赖它的服务 |
|---|---|
| `wms-admin-service` | inbound、outbound、transfer、stocktake、return、report、integration（OMS 主数据校验） |
| `wms-inventory-service` | inbound、outbound、transfer、stocktake、return、report |
| `wms-outbound-service` | integration |
| `wms-integration-service` | outbound（CTM 回调方向） |

> 说明：outbound → integration（回调 CTM 状态）与 integration → outbound（建单/取消）是两个不同方向的业务调用，服务级存在双向 Feign，属既定设计；各自只调对方暴露的 `/internal/**` 契约。

## 4. 公共契约（wms-common，签名一次定死）

所有 Feign 接口与 DTO 已落在 `wms-common`（包 `com.jim.wms.common.api` / `com.jim.wms.common.dto` / `com.jim.wms.common.event`）。下游 agent 直接注入接口使用，**不得新增、改名、改路径、改字段**。Feign 按 `spring.application.name` 寻址，服务名必须与第 2 节一致。

### 4.1 InventoryApi（`wms-inventory-service`，path `/internal/inventory`）

```java
@FeignClient(name = "wms-inventory-service", path = "/internal/inventory")
public interface InventoryApi {

    /** 库存变动（加锁 -> 扣加台账 -> 写流水），requestId 幂等 */
    @PostMapping("/change-stock")
    Result<InventoryView> changeStock(@RequestBody StockChangeCmd cmd);

    /** 冻结 / 解冻 */
    @PostMapping("/freeze")
    Result<Void> freeze(@RequestBody FreezeCmd cmd);

    /** 按仓库 + SKU 查询库存台账 */
    @GetMapping("/query")
    Result<List<InventoryView>> query(@RequestParam("warehouseCode") String warehouseCode,
                                    @RequestParam("skuCode") String skuCode);

    /** 仓库 + SKU 实物在库总量 */
    @GetMapping("/total-on-hand")
    Result<BigDecimal> totalOnHand(@RequestParam("warehouseCode") String warehouseCode,
                                 @RequestParam("skuCode") String skuCode);

    /** 按仓库列出全部库存（报表聚合用），warehouseCode 可空表示全仓 */
    @GetMapping("/list")
    Result<List<InventoryView>> list(@RequestParam(value = "warehouseCode", required = false) String warehouseCode);
}
```

### 4.2 AdminApi（`wms-admin-service`，path `/internal/admin`）

```java
@FeignClient(name = "wms-admin-service", path = "/internal/admin")
public interface AdminApi {

    @GetMapping("/warehouse/{code}")
    Result<WarehouseView> getWarehouse(@PathVariable("code") String code);

    @GetMapping("/location/{code}")
    Result<LocationView> getLocation(@PathVariable("code") String code,
                                   @RequestParam(value = "warehouseCode", required = false) String warehouseCode);

    @GetMapping("/sku/{skuCode}")
    Result<SkuView> getSku(@PathVariable("skuCode") String skuCode);
}
```

### 4.3 OutboundApi（`wms-outbound-service`，path `/internal/outbound`）

```java
@FeignClient(name = "wms-outbound-service", path = "/internal/outbound")
public interface OutboundApi {

    /** 按 CTM 补货 / 调拨申请单创建出库单，返回 WMS 出库单号 wmsOutboundNo */
    @PostMapping("/replenish-order")
    Result<String> replenishOrder(@RequestBody ReplenishOrderCmd cmd);

    /** 取消出库单 */
    @PostMapping("/cancel")
    Result<Void> cancel(@RequestBody CancelOutboundCmd cmd);

    /** 按 OMS 销售出库指令创建出库单，返回 WMS 出库单号 wmsOutboundNo */
    @PostMapping("/sales-order")
    Result<String> createSalesOrder(@RequestBody OmsOutboundOrderCmd cmd);
}
```

### 4.4 IntegrationApi（`wms-integration-service`，path `/internal/integration`）

```java
@FeignClient(name = "wms-integration-service", path = "/internal/integration")
public interface IntegrationApi {

    /** 外部单据幂等登记（CTM / OMS / ERP 单据号唯一） */
    @PostMapping("/external-order/register")
    Result<Void> registerExternalOrder(@RequestBody ExternalOrderRegisterCmd cmd);

    /** WMS 主动回调 CTM 单据状态 */
    @PostMapping("/ctm/callback")
    Result<Void> ctmCallback(@RequestBody CtmCallbackCmd cmd);

    /** WMS 主动通知 OMS 单据状态 */
    @PostMapping("/oms/status")
    Result<Void> notifyOmsStatus(@RequestBody OmsStatusNotifyCmd cmd);
}
```

### 4.5 DTO 字段（camelCase，对齐旧实体命名，不得自创字段名）

| DTO | 字段 |
|---|---|
| `StockChangeCmd` | `warehouseCode`、`locationCode`、`skuCode`、`batchNo`、`changeQty`(BigDecimal)、`businessType`、`businessNo`、`remark`、`requestId`（幂等键 / biz_key）；`@Data @Builder @NoArgsConstructor @AllArgsConstructor` |
| `FreezeCmd` | `warehouseCode`、`locationCode`、`skuCode`、`batchNo`、`qty`(BigDecimal) |
| `InventoryView` | `warehouseCode`、`locationCode`、`skuCode`、`batchNo`、`expireDate`(LocalDate)、`snCode`、`qty`、`freezeQty`、`stockStatus`(Integer) |
| `WarehouseView` | `warehouseCode`、`warehouseName`、`warehouseType`(Integer)、`enable`(Integer) |
| `LocationView` | `warehouseCode`、`areaCode`、`locationCode`、`locationType`(Integer)、`capacity`(BigDecimal)、`enable`(Integer) |
| `SkuView` | `skuCode`、`skuName`、`unit`、`batchManage`(Integer)、`expireManage`(Integer)、`snManage`(Integer)、`enable`(Integer) |
| `ReplenishOrderCmd` | `ctmOrderNo`、`orderType`、`sourceWarehouseCode`、`targetWarehouseCode`、`priority`、`items: List<Item>`；`Item`：`skuCode`、`batchNo`、`qty`(BigDecimal) |
| `CancelOutboundCmd` | `ctmOrderNo`、`wmsOutboundNo` |
| `ExternalOrderRegisterCmd` | `sourceSystem`、`externalOrderNo`、`businessType`、`warehouseCode`、`rawJson` |
| `CtmCallbackCmd` | `orderNo`、`status`、`message` |
| `OmsOutboundOrderCmd` | `omsOrderNo`(@NotBlank，幂等键)、`warehouseCode`(@NotBlank)、`remark`(可空)、`items: List<Item>`(@NotNull)；`Item`：`skuCode`(@NotBlank)、`locationCode`(@NotBlank)、`batchNo`(可空)、`qty`(@NotNull 且 >0)。订单类型服务端固定 `SALE_OUT`，不接受外部传入 |
| `OmsOutboundCancelCmd` | `omsOrderNo`(可空)、`wmsOutboundNo`(可空)、`reason`；两者不能同时为空（服务端校验返回 400） |
| `OmsStatusNotifyCmd` | `omsOrderNo`、`orderNo`、`status`、`message` |

### 4.6 事件契约

- `event.WmsStockEvent`：字段与旧单体 `com.jim.wms.integration.event.WmsStockEvent` 完全一致 —— `eventId`、`eventType`、`warehouseCode`、`locationCode`、`skuCode`、`batchNo`、`businessNo`、`changeQty`、`beforeQty`、`afterQty`、`eventTime`。
- `event.WmsEventTopics`：`TOPIC_STOCK_EVENT = "wms-stock-event"`、`TOPIC_ORDER_STATUS = "wms-order-status"`。**topic 名不可改。**

### 4.7 common 自动装配说明

`wms-common` 是 jar，包名 `com.jim.wms.common` 不在各服务组件扫描范围内；已通过
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
注册 `CommonAutoConfiguration`，每个服务自动获得：统一异常处理（`GlobalExceptionHandler`）、MyBatis-Plus 分页/乐观锁（`MybatisPlusConfig`）、CORS（`WebConfig`）、`RedisLockUtil`。下游不要重复编写这些配置。

## 5. 服务骨架约定

### 5.1 Application 类

- 包名：`com.jim.wms.<模块>`；启动类 `com.jim.wms.<模块>.<Xxx>ServiceApplication`。
- 注解：`@SpringBootApplication`、`@EnableDiscoveryClient`、`@EnableFeignClients(basePackages = "com.jim.wms.common.api")`、`@MapperScan("com.jim.wms.<模块>.mapper")`。
- `wms-outbound-service` 额外加 `@EnableScheduling`（补偿重试用）。
- 注意：`wms-return-service` 的 Java 包名沿用旧单体为 `com.jim.wms.returns`（`return` 是 Java 关键字，不能做包名），其 `@MapperScan("com.jim.wms.returns.mapper")`。

### 5.2 统一 application.yml 要点

每个服务 `src/main/resources/application.yml` 已由骨架生成，关键项：

- `server.port`：见第 2 节。
- `spring.application.name`：**必须严格等于模块名**（Feign 按此名寻址）。
- 数据源：`jdbc:mysql://127.0.0.1:3306/<本服务库>?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false`，root/root，Hikari 连接池。
- Flyway：`enabled: true`、`baseline-on-migrate: true`、`locations: classpath:db/migration`。
- MyBatis-Plus：`mapper-locations: classpath*:mapper/**/*.xml`、`map-underscore-to-camel-case: true`、`banner: false`、`id-type: auto`。
- Eureka：`eureka.client.service-url.defaultZone=http://127.0.0.1:8761/eureka/`；`instance-id: ${spring.application.name}:${server.port}`；`lease-renewal-interval-in-seconds: 10`；`lease-expiration-duration-in-seconds: 30`。
- OpenFeign：`spring.cloud.openfeign.client.config.default.connectTimeout: 3000`、`readTimeout: 10000`。
- 日志：`logging.level.com.jim.wms: debug`。
- Kafka（仅 inventory / outbound / integration）：`bootstrap-servers: 127.0.0.1:9092`，producer `acks: all`、`retries: 3`、String 序列化。
- `wms-integration-service` 额外：`wms.ctm.base-url: http://127.0.0.1:9000`；`wms.integration.oms.callback-url: ""`（OMS 状态回调地址，默认空：未配置只记 INFO 跳过；配置后 RestTemplate 连接 / 读超时各 3s POST JSON，失败只记 WARN 不阻断上游）。

### 5.3 启动顺序

1. `wms-eureka-server`（8761）先启动；
2. `wms-admin-service`、`wms-inventory-service` 启动（底座）；
3. 其余业务服务（inbound / outbound / transfer / stocktake / return / report / integration）再启动。

## 6. 建库建表规则

- 每个服务只在自己库内建自己的表；迁移脚本放 `src/main/resources/db/migration/`，从 `V1__init_schema.sql` 起编号。
- 表结构沿用旧单体 V1（列名、索引不变）；唯一例外是 `wms_inventory_log` 增加 `biz_key` 列与唯一索引。
- `wms-admin-service` 额外提供 `V2__seed_master_data.sql`：从备份旧 V2 提取，**只保留 admin 表相关种子数据** —— 仓库 `DC01`（上海主仓）、`DC02`（广州主仓）；库区 `RCV/STO/PICK/NG`；库位 `DC01-RCV-01`、`DC01-A-01-01`、`DC01-A-01-02`、`DC01-P-01`、`DC01-NG-01`；SKU `SKU-001`（测试商品A，管批次/效期）、`SKU-002`（测试商品B）。其他服务不得写种子主数据。
- 骨架阶段不预写任何建表 SQL；`db/migration` 仅留空目录与 `.gitkeep`，脚本由后续 agent 编写。
- 不允许任何服务建别人的表、跨库 JOIN、直连他人库表。

## 7. 强制业务规则

1. **库存实物唯一写者**：只能是 `wms-inventory-service`。其他服务（inbound / outbound / transfer / stocktake / return）一律通过 `InventoryApi.changeStock` 扣加库存，不得直接写库存台账或流水。
2. **禁止跨库 JOIN、禁止直连他人库表**：需要他人数据时走 Feign。旧 `wms-report` 直接注入 `InventoryMapper` 查 `wms_inventory` 的写法必须切断，报表改为 Feign 调 `InventoryApi./list` 聚合。
3. **报表只做 Feign 聚合**：`wms-report-service` 无业务表，汇总口径来自 inventory 返回的 `InventoryView`（在库 `qty`、冻结 `freezeQty`，可用量 = 在库 − 冻结）。
4. **WMS 不实现 CTM 的逻辑预占 / ATP / 在途**：CTM 已完成逻辑预占（Allocated），WMS 只做实物作业，不做库存锁定判断。
5. **库存流水只新增，不修改、不删除**。
6. **Kafka topic 名不变**：`wms-stock-event`、`wms-order-status`。
7. 库存变动业务类型沿用旧枚举：`PURCHASE_IN` / `SALE_OUT` / `TRANSFER_IN` / `TRANSFER_OUT` / `STOCKTAKE_ADJ` / `RETURN_IN` / `FREEZE`。

## 8. 跨库最终一致性方案（出库确认，定死，各 agent 照此实现）

`wms-outbound-service` 出库确认（旧 `OutboundServiceImpl.confirm` 拆分后）必须按下述四步走，**绝不允许出现「单已确认、库存未扣」**：

1. **本地事务置确认中**：本地事务内把出库单置 `CONFIRMING`，并为本单每个明细插入一条 `wms_outbound_stock_task`：`status=PENDING`，`request_id = 'OUT:{outboundNo}:{sku}:{location}:{batch}'`。
2. **逐条 Feign 扣减库存**：逐条调用 `InventoryApi.changeStock`，携带与任务记录相同的 `requestId`（即 biz_key）；`businessType=SALE_OUT`（补货/调拨按旧口径）。
3. **结果回写**：
   - 成功：任务置 `SUCCESS`；
   - 失败：任务置 `FAILED`，写 `last_error`、`retry_count`、`next_retry_time`，且整单状态置 `CONFIRM_FAILED`。
4. **定时补偿**：`@Scheduled` 每分钟扫描 `FAILED` 且到期（`next_retry_time` 已到）的任务重试，最多 5 次；全部任务成功后，单据才置 `FINISHED`，并推送 `wms-order-status` 事件。

### 8.1 库存侧幂等

- `wms_inventory_log` 增加 `biz_key` 列并建唯一索引；`biz_key` 即 `StockChangeCmd.requestId`。
- `changeStock` 命中同 `biz_key` 已存在记录时，**直接返回当前库存，不重复扣减**；库存流水仍只新增（幂等命中时不再插流水）。

## 9. Kafka / Redis 未启动时的既定行为（开发机现状）

当前开发机 Redis（6379）、Kafka（9092）未启动。骨架已按下列降级语义处理，业务 agent 不得改变：

- **Redis 降级**：`RedisLockUtil.tryLock` 捕获 Redis 连接/操作异常后，回退到进程内分段锁（`ConcurrentHashMap<String, ReentrantLock>`）并打 WARN 日志，注明「开发机无 Redis 时可运行，生产必须启用 Redis」。本地单机验证库存写操作可跑通；**生产环境必须启用 Redis**，否则多实例下降级为单机锁。
- **Kafka 发送失败**：只记 ERROR 日志 + 完整事件 JSON，沿用旧 `WmsEventProducer` 的 try/catch 语义，**不阻断主业务，也不新增 outbox 表**；事件缺失由后续对账/补偿兜底。
- 以上为开发机现状；生产部署必须补齐 Redis 与 Kafka。

## 10. 对外契约路径（不得变更）

> 下列为已确认不变的对外路径；其余 `/api/*`、`/integration/*` 由最终验证 agent 统一补齐清单。路径、请求字段、幂等语义、返回结构均沿用旧单体。

| 路径 | 归属服务 | 说明 |
|---|---|---|
| `GET/POST /api/master/warehouses` | `wms-admin-service` | 仓库主数据查询/维护 |
| `GET /api/inventory/on-hand` | `wms-inventory-service` | 仓库 + SKU 实物在库总量 |
| `POST /api/inbound/order` | `wms-inbound-service` | 接收入库单 |
| `POST /integration/ctm/replenish/order` | `wms-integration-service` | 接收 CTM 补货/调拨申请单 |
| `POST /integration/ctm/cancel` | `wms-integration-service` | CTM 取消单据 |
| `POST /integration/oms/outbound/order` | `wms-integration-service` | 接收 OMS 销售出库单，返回 `Result<String>`（data 为 WMS 出库单号） |
| `POST /integration/oms/outbound/cancel` | `wms-integration-service` | OMS 取消出库单（按 `wmsOutboundNo` 或 `omsOrderNo` 解析） |

### 10.1 `/integration/ctm/**` 承接约定

- `/integration/ctm/replenish/order` 与 `/integration/ctm/cancel` 由 `wms-integration-service` 承接（旧单体中它们在 outbound 的 `CtmReplenishController`，拆分后归位 integration）。
- 路径、请求字段、幂等语义、返回 `Result<String>`（`data` 为 `wmsOutboundNo` 出库单号）全部保持不变。
- 内部流程：先在 integration 库 `wms_external_order` 做幂等登记（`IntegrationApi.registerExternalOrder` / 本地落库），再 Feign 调 `OutboundApi.replenishOrder` 建单、`OutboundApi.cancel` 取消。
- `wms_external_order` 表归 `jim_wms_integration` 库，其他服务不得直连。

### 10.2 `/integration/oms/**` 承接约定

- `/integration/oms/outbound/order` 与 `/integration/oms/outbound/cancel` 由 `wms-integration-service` 承接。
- 建单流程：先经 `AdminApi` 校验仓库、明细库位（含归属仓库）与商品存在性，任一不存在返回 `body.code=400` 并指明缺失编码（`仓库不存在: {warehouseCode}`、`库位不存在: {locationCode}`、`库位不属于该仓库: {locationCode}`、`商品不存在: {skuCode}`），校验不通过不写 `wms_external_order`；再在 `wms_external_order` 幂等登记（`sourceSystem=OMS`、`externalOrderNo=omsOrderNo`、`businessType=SALE_OUT`、`status=RECEIVED`、`raw_json` 落原始请求 JSON）；重复推送命中唯一键 `uk_external_order` 返回 `body.code=409`，文案 `单据已接收，请勿重复推送: {omsOrderNo}`；登记成功后 Feign 调 `OutboundApi.createSalesOrder`，回填 WMS 出库单号并返回。
- 取消流程：`wmsOutboundNo` 优先，缺失时按 `omsOrderNo` 查 `wms_external_order` 回填的 `outbound_no`；两者同时为空返回 400，文案 `omsOrderNo 与 wmsOutboundNo 不能同时为空`；`omsOrderNo` 查无登记返回 404 `外部单据不存在: {omsOrderNo}`；登记记录未回填出库单号返回 400 `单据尚未生成出库单号: {omsOrderNo}`。调 `OutboundApi.cancel`，outbound 返回的 code/msg 原样透传（PICKED 的 409 文案落到调用方），不归一成 502；已拣货（`PICKED` 且存在 `picked_qty>0` 明细）取消返回 409，文案 `已拣货不可直接取消，请走反向入库（退货/调拨退回）回补库存`。
- OMS 状态回调：内部端点 `POST /internal/integration/oms/status`（`IntegrationApi.notifyOmsStatus`）；出站地址取 `wms.integration.oms.callback-url`，默认空。未配置时只记 INFO 跳过；已配置则 RestTemplate（连接/读超时各 3s）POST JSON，失败只记 WARN 并返回成功，不阻断上游。
- 出库侧：`OutboundApi.createSalesOrder` 落 `orderType=SALE_OUT`、`sourceSystem=OMS`、`sourceNo=omsOrderNo`；同一 SKU 不同库位各占一行明细。

## 11. 边界与禁止项

- 骨架只交付工程结构、公共契约、配置与本规范；不写业务逻辑、不写建表 SQL。
- 不修改 `docs/MONOLITH-BASELINE.md`（另一 agent 维护）；不重写 `README.md`（最后由验证 agent 统一重写）。
- 所有改动仅限 `D:\wms\wms-backend` 内。
