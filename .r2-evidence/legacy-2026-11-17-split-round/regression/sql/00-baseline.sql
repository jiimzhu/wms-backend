SELECT 'inventory' tbl, warehouse_code, location_code, sku_code, batch_no, qty, freeze_qty FROM jim_wms_inventory.wms_inventory;
SELECT 'log' tbl, id, sku_code, business_type, business_no, change_qty, before_qty, after_qty, biz_key FROM jim_wms_inventory.wms_inventory_log ORDER BY id;
SELECT 'order' tbl, outbound_no, source_no, order_type, status, warehouse_code, target_warehouse_code FROM jim_wms_outbound.wms_outbound_order ORDER BY id;
SELECT 'task' tbl, outbound_no, sku_code, status, change_qty, retry_count, next_retry_time, last_error FROM jim_wms_outbound.wms_outbound_stock_task ORDER BY id;
SELECT 'ext' tbl, source_system, external_order_no, business_type, status FROM jim_wms_integration.wms_external_order ORDER BY id;
