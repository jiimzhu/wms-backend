# 单体 WMS 改造基线文档（MONOLITH-BASELINE）

> 测绘对象：`D:\wms\wms-backend-monolith-backup`（只读备份，未做任何修改）。
> 测绘目的：为后续按「每模块一个独立微服务、按服务拆库、Feign + Eureka 调用」的改造提供可照抄的事实基线。本文只陈述备份源码中可核实的事实；无法确认处已标注「未能确认」。
> 引用格式：`备份文件相对路径:行号`（相对 `D:\wms\wms-backend-monolith-backup`）。

## 信息栏（交付前自检结论）

| 核对项 | 数量 | 核对结论 |
| --- | --- | --- |
| 模块数 | 11 | wms-framework / wms-admin / wms-inventory / wms-integration / wms-inbound / wms-outbound / wms-transfer / wms-stocktake / wms-return / wms-report / wms-bootstrap，均已在根 `pom.xml:21-33` 登记 |
| Java 源文件 | 69 | controller 9、entity 15、mapper 15、service 接口 8、service 实现 7、config 2、exception 2、lock 1、result 2、util 1、client 1、dto 2、event 1、producer 1、vo 1、bootstrap 启动类 1，已按目录逐一清点 |
| @RestController | 9 | MasterDataController、InventoryController、InboundController、OutboundController、CtmReplenishController、TransferController、StocktakeController、ReturnController、ReportController，Grep `@RestController` 全部命中 |
| HTTP 端点 | 28 | GET 7、POST 21、PUT 0、DELETE 0；Grep `@GetMapping/@PostMapping/@PutMapping/@DeleteMapping` 逐条核对，无遗漏 |
| 数据库表 | 15 | V1__init_schema.sql 含 15 个 `CREATE TABLE`；Grep `@TableName` 命中 15 个实体，一一对应 |
| 跨模块直连 Mapper | 2 处 | ReportController → InventoryMapper；StocktakeServiceImpl → InventoryMapper |
| 现有 Feign / Eureka | 0 | Grep `Feign|Eureka|EnableFeignClients` 无命中，当前为纯进程内 Bean 调用 |
| 事务方法 | 19 | Grep `@Transactional` 命中 19 处（inventory 2、inbound 4、outbound 4、transfer 3、stocktake 3、return 3） |
| Mapper XML / 注解 SQL | 0 | 无 `resources/mapper` XML；Grep `@Select/@Update/@Insert/@Delete` 无命中，全部为 MyBatis-Plus `BaseMapper` |

技术栈事实：Spring Boot 3.2.5、Java 17、MyBatis-Plus 3.5.5、Hutool 5.8.27、MySQL 8、Redis、Kafka、Flyway（根 `pom.xml:7-10,35-40`）。

---

## 1. 模块清单与模块间依赖

### 1.1 Maven 依赖关系（依据各模块 `pom.xml`）

| 模块 | 依赖的内部业务模块 | 其他关键依赖 |
| --- | --- | --- |
| wms-framework | 无 | spring-boot-starter-web、validation、data-redis、spring-kafka、mybatis-plus-spring-boot3-starter、hutool-all、lombok(optional)（`wms-framework/pom.xml:17-47`） |
| wms-admin | wms-framework | lombok(optional)（`wms-admin/pom.xml:17-27`） |
| wms-inventory | wms-framework、**wms-integration** | lombok(optional)（`wms-inventory/pom.xml:17-30`） |
| wms-integration | wms-framework | lombok(optional)（`wms-integration/pom.xml:17-26`） |
| wms-inbound | wms-framework、wms-admin、wms-inventory、wms-integration | lombok(optional)（`wms-inbound/pom.xml:17-38`） |
| wms-outbound | wms-framework、wms-admin、wms-inventory、wms-integration | lombok(optional)（`wms-outbound/pom.xml:17-38`） |
| wms-transfer | wms-framework、wms-inventory、wms-integration | lombok(optional)（`wms-transfer/pom.xml:17-29`）。**代码中未实际使用 inventory / integration，见陷阱 T-12** |
| wms-stocktake | wms-framework、wms-inventory | lombok(optional)（`wms-stocktake/pom.xml:17-30`） |
| wms-return | wms-framework、wms-inventory | lombok(optional)（`wms-return/pom.xml:17-29`） |
| wms-report | wms-framework、wms-inventory | lombok(optional)（`wms-report/pom.xml:17-29`） |
| wms-bootstrap | framework + admin/inventory/integration/inbound/outbound/transfer/stocktake/return/report 全部 10 个业务模块 | mysql-connector-j(runtime)、flyway-core、flyway-mysql、spring-boot-starter-test(test)（`wms-bootstrap/pom.xml:17-77`） |

依赖方向要点：
- `wms-inventory → wms-integration`：库存服务依赖集成模块（复用 `WmsEventProducer`、`WmsStockEvent`、`StockChangeCmd` 所在包）。
- `wms-inbound / wms-outbound` 的 pom 声明了 `wms-admin`，但源码中未注入任何 admin 类（Grep admin import 无业务调用），属未使用的声明依赖。
- `wms-transfer` 的 pom 声明了 `wms-inventory`、`wms-integration`，源码同样未使用。

### 1.2 各类清单（全限定名 + 职责）

#### wms-framework（公共底座）

| 全限定名 | 职责一句话 |
| --- | --- |
| `com.jim.wms.framework.result.Result<T>` | 统一返回体 `{code, msg, data}`，提供 `success(data)/success()/fail(code,msg)` 静态工厂（`result/Result.java:11-35`） |
| `com.jim.wms.framework.result.PageResult<T>` | 分页返回体 `{total, pageNum, pageSize, records}`，当前无任何 Controller 使用（`result/PageResult.java:12-26`） |
| `com.jim.wms.framework.exception.BusinessException` | 可预期业务异常，携带 `Integer code`；无 code 构造器默认 500（`exception/BusinessException.java:9-21`） |
| `com.jim.wms.framework.exception.GlobalExceptionHandler` | `@RestControllerAdvice`，处理 BusinessException、参数校验异常、兜底异常并转成 `Result`（`exception/GlobalExceptionHandler.java:15-39`） |
| `com.jim.wms.framework.lock.RedisLockUtil` | 基于 `StringRedisTemplate.setIfAbsent` 的简单分布式锁：`tryLock/unlock/executeWithLock`（`lock/RedisLockUtil.java:17-50`） |
| `com.jim.wms.framework.config.MybatisPlusConfig` | MyBatis-Plus 拦截器：乐观锁 + MySQL 分页（`config/MybatisPlusConfig.java:14-22`） |
| `com.jim.wms.framework.config.WebConfig` | 全局 CORS：`/**`，允许所有来源/方法/头，maxAge 3600（`config/WebConfig.java:11-20`） |
| `com.jim.wms.framework.util.IdGenerator` | Hutool 雪花 ID 与 `前缀+毫秒时间戳` 单号生成（`util/IdGenerator.java:8-28`） |

#### wms-admin（主数据）

| 全限定名 | 职责一句话 |
| --- | --- |
| `com.jim.wms.admin.controller.MasterDataController` | 仓库/库区/库位/SKU 查询与仓库建单 HTTP 入口（`controller/MasterDataController.java:25-52`） |
| `com.jim.wms.admin.service.MasterDataService` | 主数据服务接口：仓库/库区/库位/SKU 列表、建仓、按 SKU 取详情（`service/MasterDataService.java:10-22`） |
| `com.jim.wms.admin.service.impl.MasterDataServiceImpl` | 主数据服务实现：查重、默认值、按仓/编码查询（`service/impl/MasterDataServiceImpl.java:22-79`） |
| `com.jim.wms.admin.entity.Warehouse` | 仓库（DC）实体，表 `wms_warehouse`（`entity/Warehouse.java:16-32`） |
| `com.jim.wms.admin.entity.WarehouseArea` | 库区实体，表 `wms_area`（`entity/WarehouseArea.java:15-27`） |
| `com.jim.wms.admin.entity.WarehouseLocation` | 库位实体，表 `wms_location`（`entity/WarehouseLocation.java:16-33`） |
| `com.jim.wms.admin.entity.SkuItem` | SKU 商品主数据实体，表 `wms_sku`（`entity/SkuItem.java:15-36`） |
| `com.jim.wms.admin.mapper.WarehouseMapper` | `BaseMapper<Warehouse>` |
| `com.jim.wms.admin.mapper.WarehouseAreaMapper` | `BaseMapper<WarehouseArea>` |
| `com.jim.wms.admin.mapper.WarehouseLocationMapper` | `BaseMapper<WarehouseLocation>` |
| `com.jim.wms.admin.mapper.SkuItemMapper` | `BaseMapper<SkuItem>` |

#### wms-inventory（库存核心）

| 全限定名 | 职责一句话 |
| --- | --- |
| `com.jim.wms.inventory.controller.InventoryController` | 库存查询、在库对账、冻结 HTTP 入口（`controller/InventoryController.java:23-56`） |
| `com.jim.wms.inventory.service.InventoryService` | 库存服务接口：`changeStock/freeze/queryByWarehouseAndSku/totalOnHand`（`service/InventoryService.java:8-22`） |
| `com.jim.wms.inventory.service.impl.InventoryServiceImpl` | 库存变动唯一入口：加锁 → 行锁查台账 → 扣加台账 → 写流水 → 发 Kafka 事件（`service/impl/InventoryServiceImpl.java:29-189`） |
| `com.jim.wms.inventory.dto.StockChangeCmd` | 库存变动指令（Builder）：仓/库位/SKU/批次/变动量/业务类型/业务单号/备注（`dto/StockChangeCmd.java:16-41`） |
| `com.jim.wms.inventory.entity.Inventory` | 实物库存台账，表 `wms_inventory`，带 `@Version` 乐观锁（`entity/Inventory.java:19-48`） |
| `com.jim.wms.inventory.entity.InventoryLog` | 库存流水（只新增），表 `wms_inventory_log`（`entity/InventoryLog.java:17-43`） |
| `com.jim.wms.inventory.mapper.InventoryMapper` | `BaseMapper<Inventory>` |
| `com.jim.wms.inventory.mapper.InventoryLogMapper` | `BaseMapper<InventoryLog>` |

#### wms-integration（集成网关）

| 全限定名 | 职责一句话 |
| --- | --- |
| `com.jim.wms.integration.service.ExternalOrderService` | 外部单据幂等登记与状态回写：`register/updateStatus/get`（`service/ExternalOrderService.java:17-56`） |
| `com.jim.wms.integration.producer.WmsEventProducer` | Kafka 生产者：库存事件 topic `wms-stock-event`、单据状态 topic `wms-order-status`，失败只记日志（`producer/WmsEventProducer.java:17-47`） |
| `com.jim.wms.integration.client.CtmCallbackClient` | 主动回调 CTM 的 RestTemplate 客户端（`client/CtmCallbackClient.java:18-39`） |
| `com.jim.wms.integration.dto.CtmReplenishOrderDTO` | CTM 下发补货/调拨申请单 DTO，含校验注解（`dto/CtmReplenishOrderDTO.java:16-47`） |
| `com.jim.wms.integration.event.WmsStockEvent` | 库存变动事件体（Kafka 报文）（`event/WmsStockEvent.java:14-38`） |
| `com.jim.wms.integration.entity.ExternalOrder` | 外部单据登记实体，表 `wms_external_order`（`entity/ExternalOrder.java:16-39`） |
| `com.jim.wms.integration.mapper.ExternalOrderMapper` | `BaseMapper<ExternalOrder>` |

#### wms-inbound（入库）

| 全限定名 | 职责一句话 |
| --- | --- |
| `com.jim.wms.inbound.controller.InboundController` | 入库建单/收货/质检/上架 HTTP 入口（`controller/InboundController.java:21-69`） |
| `com.jim.wms.inbound.service.InboundService` | 入库服务接口：`create/receive/qc/putaway`（`service/InboundService.java:7-19`） |
| `com.jim.wms.inbound.service.impl.InboundServiceImpl` | 入库作业实现：收货累加、质检分流、上架加库存、完成判定（`service/impl/InboundServiceImpl.java:26-153`） |
| `com.jim.wms.inbound.entity.InboundOrder` | 入库单，表 `wms_inbound_order`；`items` 为非持久化传输载体（`entity/InboundOrder.java:19-46`） |
| `com.jim.wms.inbound.entity.InboundOrderItem` | 入库明细，表 `wms_inbound_item`（`entity/InboundOrderItem.java:16-40`） |
| `com.jim.wms.inbound.mapper.InboundOrderMapper` | `BaseMapper<InboundOrder>` |
| `com.jim.wms.inbound.mapper.InboundOrderItemMapper` | `BaseMapper<InboundOrderItem>` |

#### wms-outbound（出库）

| 全限定名 | 职责一句话 |
| --- | --- |
| `com.jim.wms.outbound.controller.OutboundController` | OMS/PDA 出库建单/拣货/确认/取消 HTTP 入口（`controller/OutboundController.java:21-66`） |
| `com.jim.wms.outbound.controller.CtmReplenishController` | CTM 补货/调拨下单与取消入口；物理位于 wms-outbound 模块（`controller/CtmReplenishController.java:30-79`） |
| `com.jim.wms.outbound.service.OutboundService` | 出库服务接口：`create/pick/confirm/cancel`（`service/OutboundService.java:7-19`） |
| `com.jim.wms.outbound.service.impl.OutboundServiceImpl` | 出库作业实现：拣货登记、确认逐条扣减、完成判定、状态事件与 CTM 回调（`service/impl/OutboundServiceImpl.java:27-163`） |
| `com.jim.wms.outbound.entity.OutboundOrder` | 出库单，表 `wms_outbound_order`；`items` 为非持久化传输载体（`entity/OutboundOrder.java:22-48`） |
| `com.jim.wms.outbound.entity.OutboundOrderItem` | 出库明细，表 `wms_outbound_item`（`entity/OutboundOrderItem.java:13-28`） |
| `com.jim.wms.outbound.mapper.OutboundOrderMapper` | `BaseMapper<OutboundOrder>` |
| `com.jim.wms.outbound.mapper.OutboundOrderItemMapper` | `BaseMapper<OutboundOrderItem>` |

#### wms-transfer（调拨跟踪）

| 全限定名 | 职责一句话 |
| --- | --- |
| `com.jim.wms.transfer.controller.TransferController` | 调拨建单/出库/收货 HTTP 入口（`controller/TransferController.java:16-40`） |
| `com.jim.wms.transfer.service.TransferService` | 调拨服务接口：`create/markOutbound/markReceived`（`service/TransferService.java:5-13`） |
| `com.jim.wms.transfer.service.impl.TransferServiceImpl` | 调拨单状态机：CREATED → IN_TRANSIT → FINISHED（`service/impl/TransferServiceImpl.java:17-66`） |
| `com.jim.wms.transfer.entity.TransferOrder` | 跨 DC 调拨单跟踪实体，表 `wms_transfer_order`（`entity/TransferOrder.java:19-44`） |
| `com.jim.wms.transfer.mapper.TransferOrderMapper` | `BaseMapper<TransferOrder>` |

#### wms-stocktake（盘点）

| 全限定名 | 职责一句话 |
| --- | --- |
| `com.jim.wms.stocktake.controller.StocktakeController` | 盘点建单/录盘/审核 HTTP 入口（`controller/StocktakeController.java:18-51`） |
| `com.jim.wms.stocktake.service.StocktakeService` | 盘点服务接口：`create/record/approve`（`service/StocktakeService.java:6-14`） |
| `com.jim.wms.stocktake.service.impl.StocktakeServiceImpl` | 盘点实现：按库存生成盘点行、录盘算差异、审核后调库存调整（`service/impl/StocktakeServiceImpl.java:27-110`） |
| `com.jim.wms.stocktake.entity.StocktakeOrder` | 盘点单，表 `wms_stocktake_order`（`entity/StocktakeOrder.java:13-30`） |
| `com.jim.wms.stocktake.entity.StocktakeItem` | 盘点明细，表 `wms_stocktake_item`（`entity/StocktakeItem.java:13-35`） |
| `com.jim.wms.stocktake.mapper.StocktakeOrderMapper` | `BaseMapper<StocktakeOrder>` |
| `com.jim.wms.stocktake.mapper.StocktakeItemMapper` | `BaseMapper<StocktakeItem>` |

#### wms-return（退货逆向，注意包名 `returns`）

| 全限定名 | 职责一句话 |
| --- | --- |
| `com.jim.wms.returns.controller.ReturnController` | 退货建单/质检/上架 HTTP 入口（`controller/ReturnController.java:16-47`） |
| `com.jim.wms.returns.service.ReturnService` | 退货服务接口：`create/inspect/putaway`（`service/ReturnService.java:5-13`） |
| `com.jim.wms.returns.service.impl.ReturnServiceImpl` | 退货实现：质检分流（SALEABLE/DEFECT/SCRAP）、可售回流加库存（`service/impl/ReturnServiceImpl.java:20-86`） |
| `com.jim.wms.returns.entity.ReturnOrder` | 退货单，表 `wms_return_order`（`entity/ReturnOrder.java:17-44`） |
| `com.jim.wms.returns.mapper.ReturnOrderMapper` | `BaseMapper<ReturnOrder>` |

#### wms-report（报表）

| 全限定名 | 职责一句话 |
| --- | --- |
| `com.jim.wms.report.controller.ReportController` | 库存汇总报表 HTTP 入口，直接注入 `InventoryMapper`（`controller/ReportController.java:26-56`） |
| `com.jim.wms.report.vo.StockSummaryVO` | 汇总 VO：`warehouseCode/skuCode/onHandQty/freezeQty/availableQty`（`vo/StockSummaryVO.java:11-21`） |

#### wms-bootstrap（启动装配）

| 全限定名 / 资源 | 职责一句话 |
| --- | --- |
| `com.jim.wms.WmsApplication` | Spring Boot 启动类：组件扫描 `com.jim.wms`，`@MapperScan` 8 个 mapper 包，`@EnableScheduling`（`WmsApplication.java:8-19`） |
| `wms-bootstrap/src/main/resources/application.yml` | 数据源/Redis/Kafka/Flyway/MyBatis-Plus/CTM 地址/日志级别全部配置 |
| `wms-bootstrap/src/main/resources/db/migration/V1__init_schema.sql` | 15 张表初始化脚本 |
| `wms-bootstrap/src/main/resources/db/migration/V2__seed_master_data.sql` | 基础主数据种子（2 仓 / 4 库区 / 5 库位 / 2 SKU） |

---

## 2. 对外 HTTP 契约全表

### 2.0 统一返回结构与业务码语义

- 返回体 `Result<T>`：`{ code: Integer, msg: String, data: T }`（`framework/result/Result.java:11-15`）。
- 成功：`code=200`，`msg="ok"`，`data` 为业务对象（`Result.java:17-27`）。
- 业务异常：`code` 取自 `BusinessException.code`，`data=null`（`GlobalExceptionHandler.java:17-21`）。
- 参数校验失败：`code=400`，`msg` 取字段校验 message（`GlobalExceptionHandler.java:23-33`）。
- 未捕获异常：`code=500`，`msg="系统内部错误: " + 异常类名`（`GlobalExceptionHandler.java:35-39`）。
- **关键事实**：异常处理器未设置 HTTP 状态码（无 `@ResponseStatus`、无 `ResponseEntity`），响应 HTTP 状态仍为 200，业务语义只看 body 里的 `code`。
- 业务码取值与出处：`400` 参数/业务校验失败；`404` 单据/明细/库存不存在；`409` 冲突（仓库编码重复、外部单据重复推送）；`429` 库存维度锁冲突；`500` 兜底。

### 2.1 MasterDataController — `/api/master`（wms-admin）

文件：`wms-admin/src/main/java/com/jim/wms/admin/controller/MasterDataController.java`

| # | 方法 | 完整路径 | 参数 / 请求体字段（类型，必填） | 返回结构 | 状态码 / 业务规则 |
| --- | --- | --- | --- | --- | --- |
| 1 | GET | `/api/master/warehouses` | 无参数 | `Result<List<Warehouse>>`；Warehouse：`id Long, warehouseCode String, warehouseName String, warehouseType Integer(1实体/2虚拟/3门店中转), enable Integer, createTime/updateTime LocalDateTime` | 成功 200；按 warehouseCode 升序（`MasterDataServiceImpl.java:30-33`） |
| 2 | POST | `/api/master/warehouses` | Body `Warehouse`：`warehouseCode String`（必填，service 非空校验）、`warehouseName String` 可选、`warehouseType Integer` 可选、`enable Integer` 可选（缺省 1） | `Result<Warehouse>` | warehouseCode 空 → 400「仓库编码不能为空」；编码已存在 → 409「仓库编码已存在: xxx」（`MasterDataServiceImpl.java:36-51`） |
| 3 | GET | `/api/master/areas` | Query `warehouseCode String`（`@RequestParam`，必填） | `Result<List<WarehouseArea>>`；字段：`id, warehouseCode, areaCode, areaName, areaType Integer(1收货/2存储/3拣货/4不合格/5发货/6退货)` | 成功 200；按仓过滤（`MasterDataController.java:39-42`） |
| 4 | GET | `/api/master/locations` | Query `warehouseCode String`（必填） | `Result<List<WarehouseLocation>>`；字段：`id, warehouseCode, areaCode, locationCode, locationType Integer, capacity BigDecimal, enable Integer` | 成功 200；按仓过滤（`MasterDataController.java:44-47`） |
| 5 | GET | `/api/master/skus` | 无参数 | `Result<List<SkuItem>>`；字段：`id, skuCode, skuName, unit, batchManage Integer, expireManage Integer, snManage Integer, enable Integer` | 成功 200（`MasterDataController.java:49-52`） |

### 2.2 InventoryController — `/api/inventory`（wms-inventory）

文件：`wms-inventory/src/main/java/com/jim/wms/inventory/controller/InventoryController.java`

| # | 方法 | 完整路径 | 参数 / 请求体字段（类型，必填） | 返回结构 | 状态码 / 业务规则 |
| --- | --- | --- | --- | --- | --- |
| 6 | GET | `/api/inventory/query` | Query `warehouseCode String`（必填）、`skuCode String`（必填） | `Result<List<Inventory>>`；Inventory：`id, warehouseCode, locationCode, skuCode, batchNo, expireDate LocalDate, snCode, qty BigDecimal, freezeQty BigDecimal, stockStatus Integer(1正常/2冻结/3残次), version Integer, createTime/updateTime` | 成功 200；按仓+SKU 列出全部库位/批次台账（`InventoryServiceImpl.java:116-120`） |
| 7 | GET | `/api/inventory/on-hand` | Query `warehouseCode String`（必填）、`skuCode String`（必填） | `Result<BigDecimal>`（在库总量，跨库位/批次求和） | 成功 200；对账口径（`InventoryServiceImpl.java:122-127`） |
| 8 | POST | `/api/inventory/freeze` | Body `FreezeRequest`（内部类 `InventoryController.java:49-56`）：`warehouseCode String`、`locationCode String`、`skuCode String`、`batchNo String`（可空）、`qty BigDecimal` | `Result<Void>` | 库存不存在 → 404「库存不存在，无法冻结」；可冻量不足 → 400「可冻库存不足，可冻=xxx」；取锁失败 → 429（`InventoryServiceImpl.java:84-112`） |

### 2.3 InboundController — `/api/inbound`（wms-inbound）

文件：`wms-inbound/src/main/java/com/jim/wms/inbound/controller/InboundController.java`

| # | 方法 | 完整路径 | 参数 / 请求体字段（类型，必填） | 返回结构 | 状态码 / 业务规则 |
| --- | --- | --- | --- | --- | --- |
| 9 | POST | `/api/inbound/order` | Body `InboundOrder`：`inboundType String(PURCHASE/TRANSFER/RETURN)`、`sourceSystem String`、`sourceNo String`、`warehouseCode String`、`items List<InboundOrderItem>`（必填，明细非空）；明细字段：`skuCode String, batchNo String, expectQty BigDecimal` | `Result<InboundOrder>`；回显生成的 `inboundNo`、`status=CREATED`；`items` 不落库仅作载体 | 明细为空 → 400「入库单明细不能为空」；单号 `IN+当前毫秒时间戳`（`InboundServiceImpl.java:35-51`） |
| 10 | POST | `/api/inbound/receive` | Body `ReceiveRequest`：`inboundNo String`、`skuCode String`、`qty BigDecimal` | `Result<Void>` | 单不存在 → 404；明细不存在 → 404；实收累计 > 预期 → 400「实收数量超过预期数量」；单状态置 RECEIVED（`InboundServiceImpl.java:56-68`） |
| 11 | POST | `/api/inbound/qc` | Body `QcRequest`：`inboundNo String`、`skuCode String`、`result String` | `Result<Void>` | `result` 仅允许 `PASS/REJECT`，否则 400「质检结论只能是 PASS 或 REJECT」（`InboundServiceImpl.java:72-79`） |
| 12 | POST | `/api/inbound/putaway` | Body `PutawayRequest`：`inboundNo String`、`skuCode String`、`locationCode String`、`qty BigDecimal` | `Result<Void>` | 质检 REJECT → 400「质检不合格，不允许正常上架到可售库位」；上架累计 > 实收 → 400「上架数量超过实收数量」；上架即调库存加账；全部明细上架完成 → 单状态 FINISHED 并发单据状态事件（`InboundServiceImpl.java:82-125`） |

### 2.4 OutboundController — `/api/outbound`（wms-outbound）

文件：`wms-outbound/src/main/java/com/jim/wms/outbound/controller/OutboundController.java`

| # | 方法 | 完整路径 | 参数 / 请求体字段（类型，必填） | 返回结构 | 状态码 / 业务规则 |
| --- | --- | --- | --- | --- | --- |
| 13 | POST | `/api/outbound/order` | Body `OutboundOrder`：`orderType String(SALE/REPLENISH/TRANSFER_OUT)`、`sourceSystem String(OMS/CTM)`、`sourceNo String`、`warehouseCode String`、`targetWarehouseCode String`、`items List<OutboundOrderItem>`（必填）；明细：`skuCode String, batchNo String, planQty BigDecimal` | `Result<OutboundOrder>`；回显 `outboundNo`、`status=CREATED` | 明细为空 → 400「出库单明细不能为空」；单号 `OUT+当前毫秒时间戳`（`OutboundServiceImpl.java:37-51`） |
| 14 | POST | `/api/outbound/pick` | Body `PickRequest`：`outboundNo String`、`skuCode String`、`locationCode String`、`qty BigDecimal` | `Result<Void>` | 单状态 FINISHED/CANCELLED → 400「单据已结束，无法继续拣货」；拣货累计 > 计划 → 400「拣货数量超过计划数量」；单状态置 PICKING（`OutboundServiceImpl.java:56-72`） |
| 15 | POST | `/api/outbound/confirm` | Body `OrderNoRequest`：`outboundNo String` | `Result<Void>` | 状态非 CREATED/PICKING → 400「当前状态不允许出库确认: xxx」；无任何拣货 → 400「尚未拣货，无法出库确认」；逐条扣减库存，完成判定后置 FINISHED/PICKED，发状态事件并回调来源系统（`OutboundServiceImpl.java:76-115`） |
| 16 | POST | `/api/outbound/cancel` | Body `CancelRequest`：`outboundNo String`、`reason String` | `Result<Void>` | 单状态 FINISHED → 400「已完成的单据不能取消」；置 CANCELLED，发状态事件并回调来源系统（`OutboundServiceImpl.java:118-129`） |

### 2.5 CtmReplenishController — `/integration/ctm`（物理位于 wms-outbound 模块）

文件：`wms-outbound/src/main/java/com/jim/wms/outbound/controller/CtmReplenishController.java`

| # | 方法 | 完整路径 | 参数 / 请求体字段（类型，必填） | 返回结构 | 状态码 / 业务规则 |
| --- | --- | --- | --- | --- | --- |
| 17 | POST | `/integration/ctm/replenish/order` | Body `CtmReplenishOrderDTO`（`wms-integration/.../dto/CtmReplenishOrderDTO.java:16-47`）：`ctmOrderNo String` **@NotBlank**（"ctmOrderNo 不能为空"，WMS 幂等键）；`orderType String` **@NotBlank**（"orderType 不能为空"，REPLENISH/TRANSFER）；`sourceWarehouseCode String` **@NotBlank**；`targetWarehouseCode String` 可空（调拨调入仓，补货可空）；`priority String` 可空；`items List<Item>` **@NotNull**（"明细不能为空"）；Item：`skuCode String` **@NotBlank**、`qty BigDecimal` **@NotNull**、`batchNo String` 可空 | `Result<String>`：data 为生成的 WMS 出库单号 `outboundNo`（`CtmReplenishController.java:61-63`） | 见下方「CTM 接口专项说明」 |
| 18 | POST | `/integration/ctm/cancel` | Body `CancelRequest`（内部类 `CtmReplenishController.java:75-79`）：`ctmOrderNo String` 可空（仅拼进取消原因）；`wmsOutboundNo String` 必填 | `Result<Void>` | `wmsOutboundNo` 为 null/空白 → 400「wmsOutboundNo 不能为空」（`CtmReplenishController.java:68-70`）；随后调 `outboundService.cancel(wmsOutboundNo, "CTM 取消单据: " + ctmOrderNo)` |

#### CTM `/replenish/order` 专项说明（幂等行为与映射规则）

- **幂等登记顺序**：Controller 先调 `externalOrderService.register("CTM", ctmOrderNo, orderType, sourceWarehouseCode, objectMapper.writeValueAsString(dto))`，再建出库单（`CtmReplenishController.java:41-42`）。
- **重复推送同一 `ctmOrderNo`**：`register` 按 `sourceSystem + externalOrderNo` 查重，已存在则抛 `BusinessException(409, "单据已接收，请勿重复推送: " + externalOrderNo)`（`wms-integration/.../service/ExternalOrderService.java:23-28`）；经全局异常处理器返回 body：`code=409`，`msg=单据已接收，请勿重复推送: <ctmOrderNo>`，`data=null`。
- **校验失败**：`@Valid` 触发 `MethodArgumentNotValidException`，body `code=400`，`msg` 为对应字段的 NotBlank/NotNull message（`GlobalExceptionHandler.java:23-33`）。
- **单据映射**（`CtmReplenishController.java:44-59`）：`orderType` 忽略大小写等于 `TRANSFER` → 出库单 `orderType=TRANSFER_OUT`，否则 `REPLENISH`；`sourceSystem="CTM"`；`sourceNo=ctmOrderNo`；`warehouseCode=sourceWarehouseCode`；`targetWarehouseCode` 透传；明细 `planQty=Item.qty`，`batchNo` 透传。
- **`/cancel` 幂等**：本接口本身不做幂等查重，直接转发出库取消；是否可取消由出库单状态判定（FINISHED 不可取消，400）。

### 2.6 TransferController — `/api/transfer`（wms-transfer）

文件：`wms-transfer/src/main/java/com/jim/wms/transfer/controller/TransferController.java`

| # | 方法 | 完整路径 | 参数 / 请求体字段（类型，必填） | 返回结构 | 状态码 / 业务规则 |
| --- | --- | --- | --- | --- | --- |
| 19 | POST | `/api/transfer/order` | Body `TransferOrder`：`sourceSystem String`、`sourceNo String`、`fromWarehouseCode String`、`toWarehouseCode String`、`skuCode String`、`batchNo String`、`qty BigDecimal` | `Result<TransferOrder>`；回显 `transferNo`、`status=CREATED` | 调出/调入仓为空 → 400「调出仓 / 调入仓不能为空」；单号 `TR+当前毫秒时间戳`（`TransferServiceImpl.java:23-32`） |
| 20 | POST | `/api/transfer/outbound` | Body `TransferNoRequest`：`transferNo String` | `Result<Void>` | 单不存在 → 404；状态非 CREATED → 400「当前状态不允许调拨出库: xxx」；置 IN_TRANSIT（`TransferServiceImpl.java:37-45`） |
| 21 | POST | `/api/transfer/received` | Body `TransferNoRequest`：`transferNo String` | `Result<Void>` | 单不存在 → 404；状态非 IN_TRANSIT → 400「调拨单不在在途状态，无法收货确认」；置 FINISHED（`TransferServiceImpl.java:49-57`） |

> 事实注记：`TransferServiceImpl` 全程不调用 `InventoryService.changeStock`，仅维护调拨单跟踪状态；实物出/入库由 outbound/inbound 流程承担。

### 2.7 StocktakeController — `/api/stocktake`（wms-stocktake）

文件：`wms-stocktake/src/main/java/com/jim/wms/stocktake/controller/StocktakeController.java`

| # | 方法 | 完整路径 | 参数 / 请求体字段（类型，必填） | 返回结构 | 状态码 / 业务规则 |
| --- | --- | --- | --- | --- | --- |
| 22 | POST | `/api/stocktake/order` | Body `StocktakeOrder`：`warehouseCode String`、`stocktakeType String(FULL/CIRCLE/MOVE)` | `Result<StocktakeOrder>`；回显 `stocktakeNo`、`status=CREATED` | 单号 `ST+当前毫秒时间戳`；按仓直查库存台账生成盘点行（`StocktakeServiceImpl.java:36-57`） |
| 23 | POST | `/api/stocktake/record` | Body `RecordRequest`：`stocktakeNo String`、`locationCode String`、`skuCode String`、`actualQty BigDecimal` | `Result<Void>` | 盘点行不存在 → 404「盘点行不存在: skuCode@locationCode」；`diffQty=actualQty-bookQty`；单状态置 COUNTING（`StocktakeServiceImpl.java:62-79`） |
| 24 | POST | `/api/stocktake/approve` | Body `StocktakeNoRequest`：`stocktakeNo String` | `Result<Void>` | 单不存在 → 404；逐行按差异调库存调整；完成后置 ADJUSTED（`StocktakeServiceImpl.java:83-109`） |

### 2.8 ReturnController — `/api/return`（wms-return）

文件：`wms-return/src/main/java/com/jim/wms/returns/controller/ReturnController.java`

| # | 方法 | 完整路径 | 参数 / 请求体字段（类型，必填） | 返回结构 | 状态码 / 业务规则 |
| --- | --- | --- | --- | --- | --- |
| 25 | POST | `/api/return/order` | Body `ReturnOrder`：`sourceSystem String`、`sourceNo String`、`warehouseCode String`、`skuCode String`、`batchNo String`、`qty BigDecimal` | `Result<ReturnOrder>`；回显 `returnNo`、`status=CREATED` | 单号 `RT+当前毫秒时间戳`（`ReturnServiceImpl.java:27-33`） |
| 26 | POST | `/api/return/inspect` | Body `InspectRequest`：`returnNo String`、`disposition String` | `Result<Void>` | `disposition` 仅允许 `SALEABLE/DEFECT/SCRAP`，否则 400「不支持的退货处置类型: xxx」；单状态置 INSPECTED（`ReturnServiceImpl.java:38-48`） |
| 27 | POST | `/api/return/putaway` | Body `PutawayRequest`：`returnNo String`、`locationCode String` | `Result<Void>` | 状态非 INSPECTED → 400「请先完成退货质检」；`SCRAP` → 置 SCRAPPED，不加库存；否则加库存后置 IN_STOCK（`ReturnServiceImpl.java:52-76`） |

### 2.9 ReportController — `/api/report`（wms-report）

文件：`wms-report/src/main/java/com/jim/wms/report/controller/ReportController.java`

| # | 方法 | 完整路径 | 参数 / 请求体字段（类型，必填） | 返回结构 | 状态码 / 业务规则 |
| --- | --- | --- | --- | --- | --- |
| 28 | GET | `/api/report/stock-summary` | Query `warehouseCode String`（`required=false`，可空） | `Result<List<StockSummaryVO>>`：`warehouseCode, skuCode, onHandQty BigDecimal, freezeQty BigDecimal, availableQty BigDecimal`；`availableQty = onHandQty - freezeQty`（`ReportController.java:35-50`） | 成功 200；按 `仓|SKU` 聚合；**直接注入 `InventoryMapper` 读 `wms_inventory`，跨模块直连**（`ReportController.java:28,32-33`） |

### 2.10 端点汇总

| 模块 | 基路径 | 端点数 | 方法分布 |
| --- | --- | --- | --- |
| wms-admin | `/api/master` | 5 | GET 4、POST 1 |
| wms-inventory | `/api/inventory` | 3 | GET 2、POST 1 |
| wms-inbound | `/api/inbound` | 4 | POST 4 |
| wms-outbound | `/api/outbound` | 4 | POST 4 |
| wms-outbound（CTM） | `/integration/ctm` | 2 | POST 2 |
| wms-transfer | `/api/transfer` | 3 | POST 3 |
| wms-stocktake | `/api/stocktake` | 3 | POST 3 |
| wms-return | `/api/return` | 3 | POST 3 |
| wms-report | `/api/report` | 1 | GET 1 |
| **合计** | — | **28** | **GET 7、POST 21、PUT 0、DELETE 0** |

---

## 3. 表读写归属矩阵（15 张表）

图例：**R**=代码读取，**W**=代码写入（insert/update/delete）；「唯一写者」指现状下对该表执行写操作的模块。种子数据（V2）由 Flyway 初始化，不计入运行时写者。

| # | 表名 | 实体 / Mapper（所在模块） | 读（R）模块 | 写（W）模块 | 现状唯一写者 | 备注 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `wms_warehouse` | `admin.entity.Warehouse` / `WarehouseMapper`（admin） | admin | admin（POST 建仓）；V2 种子 | **wms-admin** | 唯一键 `uk_warehouse_code(warehouse_code)`（V1:12） |
| 2 | `wms_area` | `admin.entity.WarehouseArea` / `WarehouseAreaMapper`（admin） | admin | 仅 V2 种子 | **wms-admin** | 唯一键 `uk_area(warehouse_code, area_code)`（V1:21） |
| 3 | `wms_location` | `admin.entity.WarehouseLocation` / `WarehouseLocationMapper`（admin） | admin | 仅 V2 种子 | **wms-admin** | 唯一键 `uk_location(warehouse_code, location_code)`（V1:32） |
| 4 | `wms_sku` | `admin.entity.SkuItem` / `SkuItemMapper`（admin） | admin | 仅 V2 种子 | **wms-admin** | 唯一键 `uk_sku_code(sku_code)`（V1:44） |
| 5 | `wms_inventory` | `inventory.entity.Inventory` / `InventoryMapper`（inventory） | **inventory**（query/on-hand/freeze/changeStock）；**report**（`ReportController` 直连）；**stocktake**（`StocktakeServiceImpl.create` 直连） | **inventory**（changeStock insert/update、freeze update） | **wms-inventory** | 唯一键 `uk_inventory(warehouse_code, location_code, sku_code, batch_no, sn_code)`，索引 `idx_wh_sku`（V1:61-62）；跨模块直连见第 4 节 C-8、C-12 |
| 6 | `wms_inventory_log` | `inventory.entity.InventoryLog` / `InventoryLogMapper`（inventory） | 现状无代码读取 | **inventory**（`writeLog` 只 insert） | **wms-inventory** | 流水只新增不改不删；索引 `idx_business_no`、`idx_wh_sku_time`（V1:78-79） |
| 7 | `wms_external_order` | `integration.entity.ExternalOrder` / `ExternalOrderMapper`（integration） | integration（register 查重、updateStatus 查单、get 查单） | **integration**（register insert、updateStatus update） | **wms-integration** | 唯一键 `uk_external_order(source_system, external_order_no)`（V1:92）；**只被 wms-integration 写** |
| 8 | `wms_inbound_order` | `inbound.entity.InboundOrder` / `InboundOrderMapper`（inbound） | inbound | inbound（create insert、receive/qc/putaway update） | **wms-inbound** | 唯一键 `uk_inbound_no(inbound_no)`（V1:105） |
| 9 | `wms_inbound_item` | `inbound.entity.InboundOrderItem` / `InboundOrderItemMapper`（inbound） | inbound | inbound（create insert、receive/qc/putaway update） | **wms-inbound** | 索引 `idx_inbound_no`（V1:118） |
| 10 | `wms_outbound_order` | `outbound.entity.OutboundOrder` / `OutboundOrderMapper`（outbound） | outbound | outbound（create/pick/confirm/cancel 写） | **wms-outbound** | 唯一键 `uk_outbound_no(outbound_no)`，索引 `idx_source_no(source_system, source_no)`（V1:132-133） |
| 11 | `wms_outbound_item` | `outbound.entity.OutboundOrderItem` / `OutboundOrderItemMapper`（outbound） | outbound | outbound（create insert、pick update） | **wms-outbound** | 索引 `idx_outbound_no`（V1:144） |
| 12 | `wms_transfer_order` | `transfer.entity.TransferOrder` / `TransferOrderMapper`（transfer） | transfer | transfer（create/markOutbound/markReceived 写） | **wms-transfer** | 唯一键 `uk_transfer_no(transfer_no)`（V1:160） |
| 13 | `wms_stocktake_order` | `stocktake.entity.StocktakeOrder` / `StocktakeOrderMapper`（stocktake） | stocktake | stocktake（create/record/approve 写） | **wms-stocktake** | 唯一键 `uk_stocktake_no(stocktake_no)`（V1:171） |
| 14 | `wms_stocktake_item` | `stocktake.entity.StocktakeItem` / `StocktakeItemMapper`（stocktake） | stocktake | stocktake（create insert、record update） | **wms-stocktake** | 索引 `idx_stocktake_no`（V1:184） |
| 15 | `wms_return_order` | `returns.entity.ReturnOrder` / `ReturnOrderMapper`（return） | return | return（create/inspect/putaway 写） | **wms-return** | 唯一键 `uk_return_no(return_no)`（V1:200） |

**必须点明的三条现状归属：**
1. `wms-report` 的 `ReportController` 直接注入 `InventoryMapper` 读 `wms_inventory`（`wms-report/.../ReportController.java:6,28,32-33`）——跨模块直连，拆库后必须切断，改为 Feign 调用 inventory 服务。
2. `wms-inventory` 是 `wms_inventory` / `wms_inventory_log` 的唯一写者；其他模块只能通过 `InventoryService.changeStock` 间接影响库存，不得直写这两张表。
3. `wms_external_order` 只被 `wms-integration` 写；其他模块不持有 `ExternalOrderMapper`。

**矩阵外的第二处跨模块直连（易漏）**：`wms-stocktake` 的 `StocktakeServiceImpl` 也直接注入了 `InventoryMapper`（`wms-stocktake/.../StocktakeServiceImpl.java:8,31`），在 `create` 时按仓查询 `wms_inventory` 生成盘点行——拆库后同样必须切断。

---

## 4. 跨模块调用清单（进程内 Bean → 拆分后形态）

> 现状全部为同一 Spring 进程内的构造器注入 Bean 调用；无 Feign、无 HTTP 内部调用。「拆分后形态」一列给出目标方向，具体接口名以改造设计为准。

| # | 发起类（模块） | 被调类（模块） | 调用方法 | 触发场景 | 拆分后应变成什么 |
| --- | --- | --- | --- | --- | --- |
| C-1 | `InboundServiceImpl`（inbound） | `InventoryService`（inventory） | `changeStock(StockChangeCmd)` | `putaway` 上架，实物库存增加（`InboundServiceImpl.java:98-107`） | Feign 调用 inventory 服务的库存变动接口；注意：现有 inventory HTTP 端点只有 query/on-hand/freeze，**无 changeStock 对外端点，需新建** |
| C-2 | `InboundServiceImpl`（inbound） | `WmsEventProducer`（integration） | `sendOrderStatus(orderNo, status, message)` | 入库单全部上架完成，发 FINISHED 状态事件（`InboundServiceImpl.java:122`） | 复用同一 Kafka topic `wms-order-status`；inbound 服务作为本地生产者发送（事件 schema 保持兼容） |
| C-3 | `OutboundServiceImpl`（outbound） | `InventoryService`（inventory） | `changeStock(StockChangeCmd)` | `confirm` 逐条拣货记录扣减库存（`OutboundServiceImpl.java:91-100`） | Feign 调用 inventory 服务库存变动接口 |
| C-4 | `OutboundServiceImpl`（outbound） | `WmsEventProducer`（integration） | `sendOrderStatus(orderNo, status, message)` | `confirm` 完成/部分出库、`cancel` 取消时发状态事件（`OutboundServiceImpl.java:112,127`） | Kafka topic `wms-order-status`；outbound 服务本地生产者 |
| C-5 | `OutboundServiceImpl`（outbound） | `CtmCallbackClient`（integration） | `callbackOrderStatus(orderNo, status, message)` | `confirm/cancel` 且 `sourceSystem="CTM"` 时回调 CTM（`OutboundServiceImpl.java:131-135`） | 对外部 CTM 的 HTTP 调用保留；若回调收口到 integration 服务，则 outbound → integration Feign 后由其出站回调 |
| C-6 | `InventoryServiceImpl`（inventory） | `WmsEventProducer`（integration） | `sendStockEvent(WmsStockEvent)` | `changeStock` 写流水后推送库存变动事件（`InventoryServiceImpl.java:172`） | Kafka topic `wms-stock-event`；inventory 服务本地生产者，或抽公共事件 SDK |
| C-7 | `StocktakeServiceImpl`（stocktake） | `InventoryService`（inventory） | `changeStock(StockChangeCmd)` | `approve` 按盘点差异调整库存（`StocktakeServiceImpl.java:96-105`） | Feign 调用 inventory 服务库存变动接口 |
| C-8 | `StocktakeServiceImpl`（stocktake） | `InventoryMapper`（inventory，**直连 Mapper**） | `selectList(按 warehouseCode 条件)` | `create` 全盘生成盘点行，取账面数量（`StocktakeServiceImpl.java:44-55`） | 切断直连 DB，改为 Feign 调用 inventory 查询接口（按仓拉取台账） |
| C-9 | `ReturnServiceImpl`（return） | `InventoryService`（inventory） | `changeStock(StockChangeCmd)` | `putaway` 退货可售回流加库存（`ReturnServiceImpl.java:64-73`） | Feign 调用 inventory 服务库存变动接口 |
| C-10 | `CtmReplenishController`（outbound 模块） | `ExternalOrderService`（integration） | `register(sourceSystem, externalOrderNo, businessType, warehouseCode, rawJson)` | CTM 下单先做幂等登记（`CtmReplenishController.java:41-42`） | Feign 调用 integration 服务的外部单据登记接口 |
| C-11 | `CtmReplenishController`（outbound 模块） | `OutboundService`（outbound） | `create(OutboundOrder)` | 幂等通过后落 WMS 出库单（`CtmReplenishController.java:61`） | 同属 outbound 服务内部调用，拆分后保持进程内；若 CTM 入口归 integration，则改为 integration → outbound Feign |
| C-12 | `ReportController`（report） | `InventoryMapper`（inventory，**直连 Mapper**） | `selectList(条件仓)` | 库存汇总报表取台账（`ReportController.java:32-33`） | 切断直连 DB，改为 Feign 调用 inventory 查询/汇总接口 |
| C-13 | `InventoryServiceImpl`（inventory） | `RedisLockUtil`（framework） | `tryLock(key, Duration)` / `unlock(key)` | 库存变动与冻结前加分布式锁（`InventoryServiceImpl.java:44,78,86,111`） | framework 作为共享底座抽成公共 starter，各服务内嵌；锁粒度与降级策略需重估 |

**现状未被调用的跨模块方法（死代码，拆分时勿误当契约）：**
- `ExternalOrderService.updateStatus(...)`、`ExternalOrderService.get(...)`：已定义但全工程无调用方（`ExternalOrderService.java:41-56`，Grep 仅命中定义处）。
- `MasterDataService.getSku(skuCode)`：已定义实现，但无 Controller 暴露、无其他类调用（`MasterDataService.java:22`，`MasterDataServiceImpl.java:72-79`）。

---

## 5. 关键业务逻辑现状摘要

> 每处不超过 8 行，供改造对照；完整逻辑以源码为准。

### 5.1 `InventoryServiceImpl.changeStock`（`wms-inventory/.../impl/InventoryServiceImpl.java:37-80`）

- **入参校验**：`changeQty` 为 null 或等于 0 → 400「变动数量不能为 0」（:39-41）。
- **Redis 锁 key 规则**：`"wms:stock:" + warehouseCode + ":" + locationCode + ":" + skuCode + ":" + safe(batchNo)`；`safe(null)="NO_BATCH"`（:42-43, :186-188）。
- **取锁**：`tryLock(lockKey, Duration.ofSeconds(10))`，失败 → 429「该库存维度正在被其他任务变更，请稍后重试」（:44-46）。
- **行锁**：`lockInventory` 按 仓/库位/SKU/批次（批次空用 `isNull`）条件查询，`wrapper.last("for update")` 加 `for update` 行锁（:129-141）。
- **库存不足判断**：`before`（无记录为 0）+ `changeQty = after`；`after < 0` → 400「库存不足, skuCode=..., 现有=..., 需要=...」（:49-54）。
- **台账写入**：无记录 insert（`freezeQty=0, stockStatus=1, version=0`）；有记录 `updateById` 改 `qty`（:55-72）。
- **流水字段**（`writeLog` :143-157）：warehouseCode/locationCode/skuCode/batchNo/businessType/businessNo/changeQty/beforeQty/afterQty/remark/createTime。
- **Kafka 事件字段与 eventType 映射**（`publishEvent` :159-173，`mapEventType` :175-184）：事件含 eventId（雪花 ID）、eventType、仓/库位/SKU/批次、businessNo、changeQty、beforeQty、afterQty、eventTime；映射：`PURCHASE_IN/RETURN_IN → INBOUND`，`SALE_OUT → OUTBOUND`，`TRANSFER_IN → TRANSFER_IN`，`TRANSFER_OUT → TRANSFER_OUT`，`STOCKTAKE_ADJ → STOCKTAKE_ADJ`，其余原样透传。
- `finally` 中 `unlock(lockKey)`（:77-79）。

### 5.2 `OutboundServiceImpl.confirm`（`wms-outbound/.../impl/OutboundServiceImpl.java:76-115`）

- **状态前置**：单据状态必须为 CREATED/PICKING，否则 400「当前状态不允许出库确认: xxx」（:78-81）。
- **逐条扣减**：遍历明细，`pickedQty > 0` 的才处理；`businessType`：`TRANSFER_OUT` 单型 → `TRANSFER_OUT`，否则 `SALE_OUT`；`changeQty = pickedQty.negate()`（:84-100）。
- **空拣货判定**：无任何拣货记录 → 400「尚未拣货，无法出库确认」（:102-104）。
- **complete 判定**：所有明细 `pickedQty >= planQty` → FINISHED，否则 PICKED（:106-108）。
- **事件与回调**：先 `sendOrderStatus(outboundNo, status, 完成? "出库完成" : "部分出库")`，再 `notifySource`；`sourceSystem="CTM"` 时回调 CTM（:112-113, :131-135）。

### 5.3 各业务节点调 `changeStock` 的时点与 businessType

| 业务模块 | 方法 | 调 changeStock 时点 | businessType 取值 | 出处 |
| --- | --- | --- | --- | --- |
| inbound | `putaway` | 质检通过、上架数量校验通过后，上架加库存 | `inboundType=TRANSFER → TRANSFER_IN`；`RETURN → RETURN_IN`；默认 `PURCHASE_IN` | `InboundServiceImpl.java:93-107` |
| outbound | `confirm` | 出库确认，逐条拣货记录扣减 | `orderType=TRANSFER_OUT → TRANSFER_OUT`；否则 `SALE_OUT` | `OutboundServiceImpl.java:90-100` |
| transfer | `markOutbound/markReceived` | **不调 changeStock**，仅改调拨单跟踪状态 | — | `TransferServiceImpl.java:37-57` |
| stocktake | `approve` | 审核通过后，逐行 `diffQty ≠ 0` 时调整库存 | `STOCKTAKE_ADJ` | `StocktakeServiceImpl.java:91-105` |
| return | `putaway` | 质检 INSPECTED 且非 SCRAP 时回流加库存 | `SALEABLE` 与 `DEFECT` 两个分支都传 `RETURN_IN`（现状代码两分支同值，见陷阱 T-8） | `ReturnServiceImpl.java:63-73` |

### 5.4 `ExternalOrderService.register`（`wms-integration/.../service/ExternalOrderService.java:21-39`）

- **幂等唯一键**：`sourceSystem + externalOrderNo` 组合（`selectCount` 查重，:23-25）。
- **重复时行为**：`exists > 0` → 抛 `BusinessException(409, "单据已接收，请勿重复推送: " + externalOrderNo)`（:26-28）。
- **写入字段**：sourceSystem、externalOrderNo、businessType、warehouseCode、`status="RECEIVED"`、rawJson、createTime/updateTime（:29-38）。
- 附带方法：`updateStatus`（按两键查，不存在 → 404；更新 status/updateTime，:41-51）；`get`（按 externalOrderNo 单键查，:53-56）。二者现状无调用方。

### 5.5 `WmsEventProducer`（`wms-integration/.../producer/WmsEventProducer.java:17-47`）

- **两个 topic 常量**：`TOPIC_STOCK_EVENT = "wms-stock-event"`（:20）；`TOPIC_ORDER_STATUS = "wms-order-status"`（:23）。
- **库存事件**：key = `warehouseCode + ":" + skuCode`，value 为事件 JSON（:31）。
- **状态事件**：key = `orderNo`，value 为 `Map{orderNo, status, message}` JSON（:41-43）。
- **失败处理**：两个方法均 `try/catch (Exception e)`，发送失败只 `log.error`，不向上抛出、不阻断主业务（:29-37, :39-47）。

### 5.6 framework 各组件行为与依赖

| 组件 | 行为 | 依赖 / 备注 |
| --- | --- | --- |
| `RedisLockUtil`（`lock/RedisLockUtil.java:17-50`） | `setIfAbsent(key, "1", expire)` 加锁；`unlock` 直接 `delete(key)`；`executeWithLock` 加锁执行 Runnable，取锁失败抛 `IllegalStateException` | 依赖 `StringRedisTemplate`；**锁 value 固定 `"1"`，无持有者标识，unlock 不校验 owner**（:22, :35）；连接异常未在工具内捕获 |
| `IdGenerator`（`util/IdGenerator.java:8-28`） | 雪花 `nextId/nextIdStr`；`nextNo(prefix)=prefix + System.currentTimeMillis()` | **不依赖 Redis、不读机器号**；雪花实例 `IdUtil.getSnowflake(1,1)`，workerId/datacenterId 硬编码 1,1（:10） |
| `GlobalExceptionHandler`（`exception/GlobalExceptionHandler.java:15-39`） | BusinessException → `Result.fail(code,msg)`；校验异常 → 400 + 字段 message；兜底 → 500 + 异常类名 | 返回 `Result<Void>`；**未设置 HTTP 状态码**，HTTP 200 承载 body.code |
| `MybatisPlusConfig`（`config/MybatisPlusConfig.java:14-22`） | 注册 `OptimisticLockerInnerInterceptor`（乐观锁）+ `PaginationInnerInterceptor(DbType.MYSQL)`（分页） | 作用于全部 MP Mapper；实体 `Inventory.version` 标注 `@Version` |
| `WebConfig`（`config/WebConfig.java:11-20`） | CORS `/**`：`allowedOriginPatterns("*")`，方法 GET/POST/PUT/DELETE/OPTIONS，头 `*`，maxAge 3600 | 无鉴权过滤器（登录鉴权在 README 中标注为待补） |

### 5.7 `wms-bootstrap` 配置与启动类

**`WmsApplication`**（`wms-bootstrap/src/main/java/com/jim/wms/WmsApplication.java:8-19`）：
- `@SpringBootApplication(scanBasePackages = "com.jim.wms")`；
- `@MapperScan` 8 个包：`admin.mapper, inventory.mapper, integration.mapper, inbound.mapper, outbound.mapper, transfer.mapper, stocktake.mapper, returns.mapper`；
- `@EnableScheduling`（工程内现状无 `@Scheduled` 方法）。

**`application.yml` 全部配置项与值**（`wms-bootstrap/src/main/resources/application.yml:1-51`）：

| 配置项 | 值 |
| --- | --- |
| `server.port` | `8081` |
| `spring.application.name` | `wms-backend` |
| `spring.profiles.active` | `dev`（备份内未见 `application-dev.yml`，未能确认是否在外部环境提供） |
| `spring.datasource.driver-class-name` | `com.mysql.cj.jdbc.Driver` |
| `spring.datasource.url` | `jdbc:mysql://127.0.0.1:3306/jim_wms?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false` |
| `spring.datasource.username` / `password` | `root` / `root` |
| `spring.datasource.hikari.maximum-pool-size` | `20` |
| `spring.datasource.hikari.minimum-idle` | `5` |
| `spring.data.redis.host` / `port` / `timeout` | `127.0.0.1` / `6379` / `3000ms` |
| `spring.kafka.bootstrap-servers` | `127.0.0.1:9092` |
| `spring.kafka.producer.acks` | `all` |
| `spring.kafka.producer.retries` | `3` |
| `spring.kafka.producer.key-serializer` | `org.apache.kafka.common.serialization.StringSerializer` |
| `spring.kafka.producer.value-serializer` | `org.apache.kafka.common.serialization.StringSerializer` |
| `spring.flyway.enabled` | `true` |
| `spring.flyway.baseline-on-migrate` | `true` |
| `spring.flyway.locations` | `classpath:db/migration` |
| `mybatis-plus.mapper-locations` | `classpath*:mapper/**/*.xml` |
| `mybatis-plus.global-config.banner` | `false` |
| `mybatis-plus.global-config.db-config.id-type` | `auto` |
| `mybatis-plus.configuration.map-underscore-to-camel-case` | `true` |
| `mybatis-plus.configuration.log-impl` | `org.apache.ibatis.logging.stdout.StdOutImpl` |
| `wms.ctm.base-url` | `http://127.0.0.1:9000` |
| `logging.level.com.jim.wms` | `debug` |

### 5.8 Flyway 脚本现状

**V1 `V1__init_schema.sql`**：15 张表，建表顺序如下（行号见表名注释）：

| 顺序 | 表 | 关键唯一键 / 索引 |
| --- | --- | --- |
| 1 | `wms_warehouse`（V1:4-13） | `uk_warehouse_code(warehouse_code)` |
| 2 | `wms_area`（V1:15-22） | `uk_area(warehouse_code, area_code)` |
| 3 | `wms_location`（V1:24-33） | `uk_location(warehouse_code, location_code)` |
| 4 | `wms_sku`（V1:35-45） | `uk_sku_code(sku_code)` |
| 5 | `wms_inventory`（V1:47-63） | `uk_inventory(warehouse_code, location_code, sku_code, batch_no, sn_code)`；`idx_wh_sku(warehouse_code, sku_code)` |
| 6 | `wms_inventory_log`（V1:65-80） | `idx_business_no(business_no)`；`idx_wh_sku_time(warehouse_code, sku_code, create_time)`；无唯一键 |
| 7 | `wms_external_order`（V1:82-93） | `uk_external_order(source_system, external_order_no)` |
| 8 | `wms_inbound_order`（V1:95-106） | `uk_inbound_no(inbound_no)` |
| 9 | `wms_inbound_item`（V1:108-119） | `idx_inbound_no(inbound_no)` |
| 10 | `wms_outbound_order`（V1:121-134） | `uk_outbound_no(outbound_no)`；`idx_source_no(source_system, source_no)` |
| 11 | `wms_outbound_item`（V1:136-145） | `idx_outbound_no(outbound_no)` |
| 12 | `wms_transfer_order`（V1:147-161） | `uk_transfer_no(transfer_no)` |
| 13 | `wms_stocktake_order`（V1:163-172） | `uk_stocktake_no(stocktake_no)` |
| 14 | `wms_stocktake_item`（V1:174-185） | `idx_stocktake_no(stocktake_no)` |
| 15 | `wms_return_order`（V1:187-201） | `uk_return_no(return_no)` |

**V2 `V2__seed_master_data.sql` 完整内容**（`V2__seed_master_data.sql:2-21`）：

- 仓库 `wms_warehouse`：
  - `DC01`，`上海主仓`，`warehouse_type=1`，`enable=1`
  - `DC02`，`广州主仓`，`warehouse_type=1`，`enable=1`
- 库区 `wms_area`（均属 DC01）：
  - `RCV`，`收货区`，`area_type=1`
  - `STO`，`存储区`，`area_type=2`
  - `PICK`，`拣货区`，`area_type=3`
  - `NG`，`不合格区`，`area_type=4`
- 库位 `wms_location`（均属 DC01）：
  - `DC01-RCV-01`，area `RCV`，`location_type=1`，`capacity=9999`，`enable=1`
  - `DC01-A-01-01`，area `STO`，`location_type=2`，`capacity=500`，`enable=1`
  - `DC01-A-01-02`，area `STO`，`location_type=2`，`capacity=500`，`enable=1`
  - `DC01-P-01`，area `PICK`，`location_type=3`，`capacity=200`，`enable=1`
  - `DC01-NG-01`，area `NG`，`location_type=4`，`capacity=500`，`enable=1`
- SKU `wms_sku`：
  - `SKU-001`，`测试商品A`，`unit=EA`，`batch_manage=1`，`expire_manage=1`，`sn_manage=0`，`enable=1`
  - `SKU-002`，`测试商品B`，`unit=EA`，`batch_manage=0`，`expire_manage=0`，`sn_manage=0`，`enable=1`

---

## 6. 改造必须注意的陷阱

### T-1 `changeStock` 当前无幂等键，拆库重试会重复扣减
- `StockChangeCmd` 仅有 warehouseCode/locationCode/skuCode/batchNo/changeQty/businessType/businessNo/remark，无 requestId 或幂等唯一键（`dto/StockChangeCmd.java:16-41`）。
- `wms_inventory_log` 表也无唯一键（V1:65-80）；`changeStock` 每次调用都 insert 一条流水并叠加台账。
- 拆成 Feign 后，调用方超时重试、消息重投都会命中同一逻辑，造成重复加/扣库存。**必须引入 request_id / biz_key 唯一约束**（流水表或库存变动记录表加唯一键），并在 inventory 服务侧做幂等判定。

### T-2 Redis 未启动时 `tryLock` 直接抛连接异常，无降级
- `RedisLockUtil.tryLock` 内部直接调 `stringRedisTemplate.setIfAbsent`（`lock/RedisLockUtil.java:22`），工具内未捕获连接异常；`InventoryServiceImpl.changeStock/freeze` 也未对取锁做容错（`InventoryServiceImpl.java:44,86`）。
- 本机实测 6379 不通时，库存变动会因 Redis 连接异常失败（事实依据：`spring.data.redis.host=127.0.0.1:6379`，`application.yml:17-21`；备份源码中无 Redis 健康检查/降级开关）。
- 拆后必须明确：Redis 不可用时锁策略降级（本地锁 / 数据库行锁兜底 / 快速失败），不能把 Redis 当成永远可用的依赖。

### T-3 report 直连 inventory 表，拆库后物理不可达
- `ReportController` 直接 `private final InventoryMapper inventoryMapper` 并 `selectList` 读 `wms_inventory`（`wms-report/.../ReportController.java:6,28,32-33`）。
- 拆库后 report 服务与 inventory 服务库表分离，直连 SQL 立即失效；必须改为 Feign 调用 inventory 的查询/汇总接口。
- 同类型第二处：`StocktakeServiceImpl.create` 也直连 `InventoryMapper` 读 `wms_inventory`（`StocktakeServiceImpl.java:8,31,44-55`），改造时一并切断，勿只改 report。

### T-4 本地 `@Transactional` 跨服务即失效，「扣减 + 改单状态」原子性会丢
- 现状 `OutboundServiceImpl.confirm` 在一个 `@Transactional(rollbackFor=Exception.class)` 内完成：逐条 `changeStock`（写 inventory 库）+ 改出库单状态（`OutboundServiceImpl.java:76-110`）；`InboundServiceImpl.putaway`、`StocktakeServiceImpl.approve`、`ReturnServiceImpl.putaway` 同理，本地事务跨两张业务表回滚。
- 拆服务后 Feign 调用不参与本地事务：库存已扣、出库单状态更新失败（或反之）将无事务回滚。需引入最终一致方案（事件对账、本地消息表、补偿/冲正），不能假设远程调用随本地事务回滚。
- 附带现状风险：`confirm` 先逐条扣减，之后才判定「尚未拣货」抛错（`OutboundServiceImpl.java:84-104`）；当前依赖本地事务回滚已扣减部分，跨服务后该保护不存在。

### T-5 Kafka 未启动时事件只留日志，不阻断业务但事件会丢
- `WmsEventProducer.sendStockEvent/sendOrderStatus` 均 `try/catch(Exception)` 只 `log.error`（`WmsEventProducer.java:29-37,39-47`）；本机 9092 不通时（`application.yml:22-28`），库存/状态事件静默丢失，靠 CTM 定时对账 `/api/inventory/on-hand` 校准。
- 拆后各服务独立发 Kafka，需明确：生产者失败是否重试、是否落库待重发、对账补偿由谁触发；README 已注明「CTM 回调失败重试表与定时补偿」为待补能力（`README.md:63`）。

### T-6 旧 bootstrap 端口 8081 与新 admin 服务端口冲突
- 旧单体 `server.port=8081`（`application.yml:1-2`）；新架构拆出的 admin/各微服务需分配独立端口，避免与旧单体或彼此冲突（Eureka 注册名与端口规划需避开 8081）。

### T-7 旧包名 `com.jim.wms.*` 与组件扫描 / `@MapperScan` 范围
- 启动类 `scanBasePackages="com.jim.wms"`，`@MapperScan` 硬编码 8 个 mapper 包（`WmsApplication.java:8-18`）。
- 注意包名不一致：退货模块包名是 `com.jim.wms.returns`（复数），其余模块为单数（`inbound/outbound/inventory/...`）。新服务若改包名，必须同步重配组件扫描与 Mapper 扫描路径，否则 Bean/Mapper 不注册。
- 现状 mapper 接口均带 `@Mapper` 注解，`@MapperScan` 与注解双保险；拆分后每个服务的扫描范围只能覆盖自身包。

### T-8 阅读中实际发现的其他耦合与隐患（均有源码依据）

- **T-8a CTM 控制器归属错位**：`CtmReplenishController` 物理打包在 `wms-outbound` 模块，却注入 `wms-integration` 的 `ExternalOrderService`（`CtmReplenishController.java:7,34`）。拆服务时需决策该入口归 integration 网关还是 outbound，避免双归属。
- **T-8b 退货残次分支也加可售库存**：`ReturnServiceImpl.putaway` 中 `businessType = "SALEABLE".equals(...) ? "RETURN_IN" : "RETURN_IN"`，两分支同值；`DEFECT`（残次）也会调 `changeStock` 加进同一台账（`ReturnServiceImpl.java:63-73`）。现状行为如此，改造时勿擅自改成残次不入账，需先与业务确认。
- **T-8c 锁无 owner 校验，可能误释放他人锁**：`RedisLockUtil` 锁 value 固定 `"1"`，`unlock` 直接 `delete(key)`，不判断持有者；锁过期后被他人重新获取，原线程解锁会删掉新持有者的锁（`RedisLockUtil.java:22,34-36`）。
- **T-8d 单号生成并发风险**：`IdGenerator.nextNo` 用 `前缀 + System.currentTimeMillis()`，同一毫秒并发可产生重复单号（`IdGenerator.java:26-28`）；雪花实例 workerId/datacenterId 硬编码 `1,1`，多实例部署会撞 ID（`IdGenerator.java:10`）。
- **T-8e 回调客户端非 Spring Bean，无超时/连接池配置**：`CtmCallbackClient` 内部 `new RestTemplate()`（`CtmCallbackClient.java:20`），未配置连接超时、读超时与连接池；CTM 不可达时线程可能长时间阻塞（异常虽被 catch，但阻塞时间不可控）。
- **T-8f 乐观锁与行锁并存**：`Inventory` 带 `@Version`（`Inventory.java:43-44`），同时 `changeStock` 用 `for update` 行锁；拆库后若去掉行锁，需确认乐观锁更新失败的重试/报错路径。
- **T-8g 序列号维度未落地**：`wms_inventory` 唯一键含 `sn_code`（V1:61），但 `changeStock` 查询与 `StockChangeCmd` 均未带 snCode（`InventoryServiceImpl.java:129-138`，`StockChangeCmd.java:16-41`）；序列号管理现状未真正生效。
- **T-8h 未使用的 pom 依赖**：`wms-inbound/wms-outbound` 声明依赖 `wms-admin`、`wms-transfer` 声明依赖 `wms-inventory/wms-integration`，源码均未使用（见 1.1）；拆分时清理依赖，避免误带数据源/Bean 扫描。
- **T-8i 全局异常不映射 HTTP 状态**：所有业务错误都以 HTTP 200 + body.code 返回（见 2.0）；拆服务后 Feign 错误解码、网关状态码对齐需以 body.code 为准，不能依赖 HTTP status。
- **T-8j 无鉴权**：现状无 Spring Security/JWT，全部端点裸奔（`README.md:61` 标注待补）；拆分后各服务不能假设已有统一鉴权。
- **T-8k 事务方法清单**：共 19 个 `@Transactional(rollbackFor=Exception.class)`（inventory 2、inbound 4、outbound 4、transfer 3、stocktake 3、return 3）；拆分时逐个核对哪些事务边界跨了服务。
- **T-8l 无 Mapper XML**：`mybatis-plus.mapper-locations=classpath*:mapper/**/*.xml`，但备份内无任何 XML；全部查询走 MP `Wrapper`，拆分后 SQL 条件随代码迁移，无独立 SQL 文件可对照。
