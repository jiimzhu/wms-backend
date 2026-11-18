-- R3 smoke-data cleanup. Keep Flyway history, admin seed, inventory seed row id10, log id80, outbox id3.
USE jim_wms_outbound;
DELETE FROM wms_outbound_stock_task;
DELETE FROM wms_outbound_item;
DELETE FROM wms_outbound_order;
DELETE FROM wms_event_outbox;

USE jim_wms_transfer;
DELETE FROM wms_transfer_order;

USE jim_wms_integration;
DELETE FROM wms_external_order;

USE jim_wms_inbound;
DELETE FROM wms_inbound_item;
DELETE FROM wms_inbound_order;
DELETE FROM wms_event_outbox;

USE jim_wms_stocktake;
DELETE FROM wms_stocktake_item;
DELETE FROM wms_stocktake_order;

USE jim_wms_return;
DELETE FROM wms_return_order;

USE jim_wms_inventory;
DELETE FROM wms_inventory_log WHERE id <> 80;
DELETE FROM wms_event_outbox WHERE id <> 3;
DELETE FROM wms_inventory WHERE NOT (warehouse_code='WMS-T' AND location_code='LOC-T' AND sku_code='SKU-T-001' AND batch_no='B20261118');
