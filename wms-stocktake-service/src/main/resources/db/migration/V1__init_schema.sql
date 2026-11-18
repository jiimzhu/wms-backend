CREATE TABLE IF NOT EXISTS wms_stocktake_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stocktake_no VARCHAR(64) NOT NULL,
    warehouse_code VARCHAR(64) NOT NULL,
    stocktake_type VARCHAR(16) NOT NULL COMMENT 'FULL / CIRCLE / MOVE',
    status VARCHAR(16) NOT NULL COMMENT 'CREATED / COUNTING / COUNTED / ADJUSTED / CANCELLED',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_stocktake_no (stocktake_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='盘点单';

CREATE TABLE IF NOT EXISTS wms_stocktake_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stocktake_no VARCHAR(64) NOT NULL,
    warehouse_code VARCHAR(64) NOT NULL,
    location_code VARCHAR(64) NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    batch_no VARCHAR(64) DEFAULT NULL,
    book_qty DECIMAL(18,3) DEFAULT 0,
    actual_qty DECIMAL(18,3) DEFAULT NULL,
    diff_qty DECIMAL(18,3) DEFAULT NULL,
    KEY idx_stocktake_no (stocktake_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='盘点明细';
