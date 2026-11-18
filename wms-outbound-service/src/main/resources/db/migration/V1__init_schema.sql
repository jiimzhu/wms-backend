-- wms-outbound-service 出库域初始化脚本
-- wms_outbound_order / wms_outbound_item 列名、类型、索引沿用单体备份 V1，不做改动；
-- wms_outbound_stock_task 为拆分后新增的出库扣库存补偿表。

CREATE TABLE IF NOT EXISTS wms_outbound_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    outbound_no VARCHAR(64) NOT NULL,
    order_type VARCHAR(16) NOT NULL COMMENT 'SALE / REPLENISH / TRANSFER_OUT',
    source_system VARCHAR(16) DEFAULT NULL COMMENT 'OMS / CTM',
    source_no VARCHAR(64) DEFAULT NULL COMMENT 'CTM 单号',
    warehouse_code VARCHAR(64) NOT NULL,
    target_warehouse_code VARCHAR(64) DEFAULT NULL,
    status VARCHAR(16) NOT NULL COMMENT 'CREATED / PICKING / PICKED / FINISHED / CANCELLED / FAILED',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_outbound_no (outbound_no),
    KEY idx_source_no (source_system, source_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出库单';

CREATE TABLE IF NOT EXISTS wms_outbound_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    outbound_no VARCHAR(64) NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    batch_no VARCHAR(64) DEFAULT NULL,
    plan_qty DECIMAL(18,3) NOT NULL,
    picked_qty DECIMAL(18,3) DEFAULT 0,
    location_code VARCHAR(64) DEFAULT NULL,
    KEY idx_outbound_no (outbound_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出库明细';

-- 出库扣库存补偿任务：本地事务先落 PENDING，再逐条 Feign 扣减；FAILED 由定时任务重试
CREATE TABLE IF NOT EXISTS wms_outbound_stock_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    outbound_no VARCHAR(40) NOT NULL,
    sku_code VARCHAR(40) NOT NULL,
    location_code VARCHAR(40) NOT NULL,
    batch_no VARCHAR(40) DEFAULT NULL,
    change_qty DECIMAL(18,4) NOT NULL COMMENT '本次扣减数量（负数）',
    request_id VARCHAR(200) NOT NULL COMMENT '幂等键（biz_key）：OUT:{outboundNo}:{skuCode}:{locationCode}:{batchNo或NOBATCH}',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / SUCCESS / FAILED / CANCELLED',
    last_error VARCHAR(500) DEFAULT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_time DATETIME DEFAULT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_outbound_task_request (request_id),
    KEY idx_outbound_no (outbound_no),
    KEY idx_status_retry (status, next_retry_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出库扣库存补偿任务';
