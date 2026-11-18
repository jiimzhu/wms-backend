-- wms-report-service 无业务表。
-- 库 jim_wms_report 仅用于承载 Flyway schema history（flyway_schema_history），
-- 库存汇总口径全部通过 Feign 调用 wms-inventory-service 获取，本服务不直连任何库存表。
-- 占位可执行语句 SELECT 1，保证迁移脚本包含合法语句、避免 Flyway 对空脚本告警；
-- 该语句不创建任何业务对象。
SELECT 1;
