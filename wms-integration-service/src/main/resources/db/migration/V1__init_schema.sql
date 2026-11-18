-- wms-integration-service 初始化结构：外部单据幂等登记表。
-- 表结构逐列沿用旧单体 V1（含原有唯一约束 uk_external_order(source_system, external_order_no)），
-- 该唯一键即 (source_system, external_order_no) 幂等强一致兜底索引。

CREATE TABLE IF NOT EXISTS wms_external_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_system VARCHAR(16) NOT NULL COMMENT 'CTM / OMS / ERP',
    external_order_no VARCHAR(64) NOT NULL,
    business_type VARCHAR(32) NOT NULL,
    warehouse_code VARCHAR(64) DEFAULT NULL,
    status VARCHAR(32) NOT NULL COMMENT 'RECEIVED / PICKING / PARTIAL / FINISHED / CANCELLED / FAILED',
    raw_json TEXT,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_external_order (source_system, external_order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外部单据幂等登记';
