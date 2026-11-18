-- wms-inbound-service 入库域初始化脚本
-- wms_inbound_order / wms_inbound_item 列名、类型、索引沿用单体备份 V1，不做改动。

CREATE TABLE IF NOT EXISTS wms_inbound_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    inbound_no VARCHAR(64) NOT NULL,
    inbound_type VARCHAR(16) NOT NULL COMMENT 'PURCHASE / TRANSFER / RETURN',
    source_system VARCHAR(16) DEFAULT NULL,
    source_no VARCHAR(64) DEFAULT NULL,
    warehouse_code VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL COMMENT 'CREATED / RECEIVED / PUTAWAY / FINISHED / CANCELLED',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_inbound_no (inbound_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='入库单';

CREATE TABLE IF NOT EXISTS wms_inbound_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    inbound_no VARCHAR(64) NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    batch_no VARCHAR(64) DEFAULT NULL,
    expect_qty DECIMAL(18,3) NOT NULL,
    received_qty DECIMAL(18,3) DEFAULT 0,
    putaway_qty DECIMAL(18,3) DEFAULT 0,
    location_code VARCHAR(64) DEFAULT NULL,
    qc_status VARCHAR(16) DEFAULT 'PENDING',
    KEY idx_inbound_no (inbound_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='入库明细';
