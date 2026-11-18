-- WMS 主数据初始化结构（wms-admin-service，库 jim_wms_admin）
-- 仅包含本服务归属的 4 张表：wms_warehouse / wms_area / wms_location / wms_sku
-- 列名、类型、索引沿用旧单体 V1，未做改动

CREATE TABLE IF NOT EXISTS wms_warehouse (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    warehouse_code VARCHAR(64) NOT NULL COMMENT '仓库编码，与 CTM 的 DC 编码一致',
    warehouse_name VARCHAR(128) NOT NULL,
    warehouse_type TINYINT DEFAULT 1 COMMENT '1 实体仓 2 虚拟仓 3 门店中转仓',
    enable TINYINT DEFAULT 1,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_warehouse_code (warehouse_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓库主数据';

CREATE TABLE IF NOT EXISTS wms_area (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    warehouse_code VARCHAR(64) NOT NULL,
    area_code VARCHAR(64) NOT NULL,
    area_name VARCHAR(128) NOT NULL,
    area_type TINYINT COMMENT '1 收货 2 存储 3 拣货 4 不合格 5 发货 6 退货',
    UNIQUE KEY uk_area (warehouse_code, area_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库区';

CREATE TABLE IF NOT EXISTS wms_location (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    warehouse_code VARCHAR(64) NOT NULL,
    area_code VARCHAR(64) NOT NULL,
    location_code VARCHAR(64) NOT NULL,
    location_type TINYINT COMMENT '1 收货位 2 存储位 3 拣货位 4 不合格位 5 发货位',
    capacity DECIMAL(18,3) DEFAULT 0,
    enable TINYINT DEFAULT 1,
    UNIQUE KEY uk_location (warehouse_code, location_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库位';

CREATE TABLE IF NOT EXISTS wms_sku (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sku_code VARCHAR(64) NOT NULL,
    sku_name VARCHAR(256) NOT NULL,
    unit VARCHAR(32) DEFAULT 'EA',
    batch_manage TINYINT DEFAULT 0,
    expire_manage TINYINT DEFAULT 0,
    sn_manage TINYINT DEFAULT 0,
    enable TINYINT DEFAULT 1,
    UNIQUE KEY uk_sku_code (sku_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品主数据';
