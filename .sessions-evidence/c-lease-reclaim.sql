-- c 租约过期回收（SQL 形态与 OutboundStockTaskMapper#claimExpired 一致）
USE jim_wms_outbound;

-- T2：PROCESSING 且租约已过期，owner 为旧实例，retry_count=2
INSERT INTO wms_outbound_stock_task
    (outbound_no, sku_code, location_code, batch_no, change_qty, request_id, status, last_error,
     retry_count, next_retry_time, create_time, update_time, owner, lease_until)
VALUES ('OUT-TEST-C', 'SKU-C', 'LOC-1', NULL, -8,
        'OUT:OUT-TEST-C:SKU-C:LOC-1:NOBATCH', 'PROCESSING', NULL,
        2, NULL, NOW(), NOW(), 'old-instance:9999:old-thread', DATE_SUB(NOW(), INTERVAL 300 SECOND));
SET @id_exp = LAST_INSERT_ID();

-- T3：PROCESSING 但租约未到期，不应被回收
INSERT INTO wms_outbound_stock_task
    (outbound_no, sku_code, location_code, batch_no, change_qty, request_id, status, last_error,
     retry_count, next_retry_time, create_time, update_time, owner, lease_until)
VALUES ('OUT-TEST-D', 'SKU-D', 'LOC-1', NULL, -8,
        'OUT:OUT-TEST-D:SKU-D:LOC-1:NOBATCH', 'PROCESSING', NULL,
        1, NULL, NOW(), NOW(), 'old-instance:9999:old-thread', DATE_ADD(NOW(), INTERVAL 300 SECOND));
SET @id_keep = LAST_INSERT_ID();

SELECT '--- 回收前快照 ---' AS step;
SELECT id, status, retry_count, owner, lease_until FROM wms_outbound_stock_task
WHERE id IN (@id_exp, @id_keep);

-- 回收过期认领（claimExpired）
UPDATE wms_outbound_stock_task
SET status='PROCESSING', owner='wms-outbound-service:18084:reclaim-thread', lease_until=DATE_ADD(NOW(), INTERVAL 60 SECOND),
    retry_count = retry_count + 1, update_time=NOW()
WHERE id=@id_exp AND status='PROCESSING' AND lease_until < NOW()
  AND retry_count < 5;
SELECT ROW_COUNT() AS expired_claim_affected_rows;

-- 未过期任务再跑同一回收条件
UPDATE wms_outbound_stock_task
SET status='PROCESSING', owner='wms-outbound-service:18084:reclaim-thread', lease_until=DATE_ADD(NOW(), INTERVAL 60 SECOND),
    retry_count = retry_count + 1, update_time=NOW()
WHERE id=@id_keep AND status='PROCESSING' AND lease_until < NOW()
  AND retry_count < 5;
SELECT ROW_COUNT() AS keep_claim_affected_rows;

SELECT '--- 回收后快照 ---' AS step;
SELECT id, status, retry_count, owner, lease_until FROM wms_outbound_stock_task
WHERE id IN (@id_exp, @id_keep);
