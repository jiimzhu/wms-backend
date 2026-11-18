-- V2: wms_external_order 回写 WMS 出库单号（OMS 销售出库链路）。
-- 登记外部单号后建单成功回填 outbound_no，取消时可按 omsOrderNo 反查出库单号。
-- 幂等唯一键 uk_external_order(source_system, external_order_no) 已在 V1 建立，
-- 本迁移只加回单号列，不改唯一键、不动既有列。

ALTER TABLE wms_external_order
    ADD COLUMN outbound_no VARCHAR(64) DEFAULT NULL COMMENT 'WMS 出库单号（建单成功后回填）' AFTER external_order_no;
