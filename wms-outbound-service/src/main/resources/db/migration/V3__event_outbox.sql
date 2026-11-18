-- V3: 事务性发件箱（规格 REFACTOR-SPEC-2026-11-18 第 1 节）
-- 业务事务内只写 PENDING 记录，真正的 Kafka 投递由 wms-common 的 OutboxRelay 在事务外完成。
CREATE TABLE IF NOT EXISTS wms_event_outbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL,
    topic VARCHAR(64) NOT NULL,
    event_key VARCHAR(128) NOT NULL,
    payload LONGTEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / SENDING / SENT / FAILED',
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_time DATETIME DEFAULT NULL,
    last_error VARCHAR(500) DEFAULT NULL,
    owner VARCHAR(64) DEFAULT NULL,
    lease_until DATETIME DEFAULT NULL,
    create_time DATETIME NOT NULL,
    update_time DATETIME NOT NULL,
    sent_time DATETIME DEFAULT NULL,
    UNIQUE KEY uk_event_id (event_id),
    KEY idx_status_next_retry (status, next_retry_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事务性发件箱';
