-- V2: 补偿任务三态认领与租约 + 明细唯一性下沉（规格 REFACTOR-SPEC-2026-11-18 第 4/5 节）
-- 1) wms_outbound_stock_task：新增 owner / lease_until 认领租约列与 (status, lease_until) 候选索引；
--    status 取值扩展为 PENDING / PROCESSING / SUCCESS / FAILED / CANCELLED（列仍为 VARCHAR，不改类型）。
-- 2) wms_outbound_item：新增生成列 batch_key 兜住 batch_no 为 NULL 的情形，
--    明细唯一性由应用层「单号+SKU」下沉到数据库维度 (outbound_no, sku_code, location_code, batch_key)。
-- 3) wms_outbound_order：新增单据级 last_error（规格第 7 节：调拨在途 404 只记 WARN + 单据级 last_error 供补扫排查）。

ALTER TABLE wms_outbound_stock_task
    ADD COLUMN owner VARCHAR(64) DEFAULT NULL COMMENT '认领者：实例:端口:线程',
    ADD COLUMN lease_until DATETIME DEFAULT NULL COMMENT '认领租约到期时间',
    ADD KEY idx_status_lease (status, lease_until);

ALTER TABLE wms_outbound_item
    ADD COLUMN batch_key VARCHAR(64) GENERATED ALWAYS AS (IFNULL(batch_no, 'NO_BATCH')) STORED,
    ADD UNIQUE KEY uk_item_dim (outbound_no, sku_code, location_code, batch_key);

ALTER TABLE wms_outbound_order
    ADD COLUMN last_error VARCHAR(500) DEFAULT NULL COMMENT '单据级最近一次失败原因（如调拨在途 404），供补扫排查';
