CREATE TABLE IF NOT EXISTS wms_transfer_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    transfer_no VARCHAR(64) NOT NULL,
    source_system VARCHAR(16) DEFAULT NULL,
    source_no VARCHAR(64) DEFAULT NULL,
    from_warehouse_code VARCHAR(64) NOT NULL,
    to_warehouse_code VARCHAR(64) NOT NULL,
    sku_code VARCHAR(64) DEFAULT NULL,
    batch_no VARCHAR(64) DEFAULT NULL,
    qty DECIMAL(18,3) DEFAULT 0,
    status VARCHAR(16) NOT NULL COMMENT 'CREATED / IN_TRANSIT / FINISHED',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_transfer_no (transfer_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跨 DC 调拨单跟踪';
