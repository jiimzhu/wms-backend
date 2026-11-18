SELECT 'jim_wms_admin' db,'wms_warehouse' t,COUNT(*) c FROM jim_wms_admin.wms_warehouse UNION ALL
SELECT 'jim_wms_admin','wms_area',COUNT(*) FROM jim_wms_admin.wms_area UNION ALL
SELECT 'jim_wms_admin','wms_location',COUNT(*) FROM jim_wms_admin.wms_location UNION ALL
SELECT 'jim_wms_admin','wms_sku',COUNT(*) FROM jim_wms_admin.wms_sku UNION ALL
SELECT 'jim_wms_inventory','wms_inventory',COUNT(*) FROM jim_wms_inventory.wms_inventory UNION ALL
SELECT 'jim_wms_inventory','wms_inventory_log',COUNT(*) FROM jim_wms_inventory.wms_inventory_log UNION ALL
SELECT 'jim_wms_inbound','wms_inbound_order',COUNT(*) FROM jim_wms_inbound.wms_inbound_order UNION ALL
SELECT 'jim_wms_inbound','wms_inbound_item',COUNT(*) FROM jim_wms_inbound.wms_inbound_item UNION ALL
SELECT 'jim_wms_outbound','wms_outbound_order',COUNT(*) FROM jim_wms_outbound.wms_outbound_order UNION ALL
SELECT 'jim_wms_outbound','wms_outbound_item',COUNT(*) FROM jim_wms_outbound.wms_outbound_item UNION ALL
SELECT 'jim_wms_outbound','wms_outbound_stock_task',COUNT(*) FROM jim_wms_outbound.wms_outbound_stock_task UNION ALL
SELECT 'jim_wms_transfer','wms_transfer_order',COUNT(*) FROM jim_wms_transfer.wms_transfer_order UNION ALL
SELECT 'jim_wms_stocktake','wms_stocktake_order',COUNT(*) FROM jim_wms_stocktake.wms_stocktake_order UNION ALL
SELECT 'jim_wms_stocktake','wms_stocktake_item',COUNT(*) FROM jim_wms_stocktake.wms_stocktake_item UNION ALL
SELECT 'jim_wms_return','wms_return_order',COUNT(*) FROM jim_wms_return.wms_return_order UNION ALL
SELECT 'jim_wms_integration','wms_external_order',COUNT(*) FROM jim_wms_integration.wms_external_order;
SELECT 'flyway' chk, TABLE_SCHEMA db, COUNT(*) history_rows FROM information_schema.tables t JOIN information_schema.columns c ON c.TABLE_SCHEMA=t.TABLE_SCHEMA AND c.TABLE_NAME=t.TABLE_NAME WHERE t.TABLE_NAME='flyway_schema_history' GROUP BY TABLE_SCHEMA ORDER BY TABLE_SCHEMA;
