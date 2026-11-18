-- WMS 库存服务初始化结构（wms-inventory-service，库 jim_wms_inventory）
-- 仅包含本服务归属的 2 张表：wms_inventory / wms_inventory_log
-- 列名、类型、索引沿用旧单体 V1；唯一允许增量：wms_inventory_log.biz_key 列 + 唯一索引 uk_inventory_log_biz_key

CREATE TABLE IF NOT EXISTS wms_inventory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    warehouse_code VARCHAR(64) NOT NULL,
    location_code VARCHAR(64) NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    batch_no VARCHAR(64) DEFAULT NULL,
    expire_date DATE DEFAULT NULL,
    sn_code VARCHAR(128) DEFAULT NULL,
    qty DECIMAL(18,3) NOT NULL DEFAULT 0 COMMENT '实物在库数量',
    freeze_qty DECIMAL(18,3) NOT NULL DEFAULT 0 COMMENT '冻结数量',
    stock_status TINYINT DEFAULT 1 COMMENT '1 正常 2 冻结 3 残次',
    version INT DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_inventory (warehouse_code, location_code, sku_code, batch_no, sn_code),
    KEY idx_wh_sku (warehouse_code, sku_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实物库存台账';

CREATE TABLE IF NOT EXISTS wms_inventory_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    warehouse_code VARCHAR(64) NOT NULL,
    location_code VARCHAR(64) NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    batch_no VARCHAR(64) DEFAULT NULL,
    business_type VARCHAR(32) NOT NULL COMMENT 'PURCHASE_IN / SALE_OUT / TRANSFER_IN / TRANSFER_OUT / STOCKTAKE_ADJ / RETURN_IN',
    business_no VARCHAR(64) NOT NULL,
    change_qty DECIMAL(18,3) NOT NULL,
    before_qty DECIMAL(18,3) NOT NULL,
    after_qty DECIMAL(18,3) NOT NULL,
    remark VARCHAR(512) DEFAULT NULL,
    -- 增量列：幂等键（StockChangeCmd.requestId）。调用方未携带 requestId 时为 NULL。
    -- MySQL InnoDB 唯一索引对 NULL 视为互不相同，多条 NULL 记录不冲突；仅非空 biz_key 参与唯一去重。
    biz_key VARCHAR(120) DEFAULT NULL COMMENT '幂等键 requestId，可空；非空时唯一',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_business_no (business_no),
    KEY idx_wh_sku_time (warehouse_code, sku_code, create_time),
    UNIQUE KEY uk_inventory_log_biz_key (biz_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存流水，只新增';
