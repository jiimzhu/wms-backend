ALTER TABLE wms_transfer_order
    ADD COLUMN outbound_no VARCHAR(64) DEFAULT NULL,
    ADD KEY idx_outbound_no (outbound_no);
