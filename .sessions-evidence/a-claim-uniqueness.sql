-- a/b 认领唯一性 + retry_count 认领时自增（SQL 形态与 OutboundStockTaskMapper#claimNew 一致）
USE jim_wms_outbound;

-- 构造一条 PENDING 候选任务（next_retry_time 已到期）
INSERT INTO wms_outbound_stock_task
    (outbound_no, sku_code, location_code, batch_no, change_qty, request_id, status, last_error,
     retry_count, next_retry_time, create_time, update_time, owner, lease_until)
VALUES ('OUT-TEST-A', 'SKU-A', 'LOC-1', NULL, -10,
        'OUT:OUT-TEST-A:SKU-A:LOC-1:NOBATCH', 'PENDING', NULL,
        0, NOW(), NOW(), NOW(), NULL, NULL);
SET @id = LAST_INSERT_ID();

SELECT '--- 认领前快照 ---' AS step;
SELECT id, status, retry_count, owner, lease_until, next_retry_time
FROM wms_outbound_stock_task WHERE id = @id;

-- 第一条条件 UPDATE（模拟并发认领者 1）
UPDATE wms_outbound_stock_task
SET status='PROCESSING', owner='wms-outbound-service:18084:thread-1', lease_until=DATE_ADD(NOW(), INTERVAL 60 SECOND),
    retry_count = retry_count + 1, update_time=NOW()
WHERE id=@id AND status IN ('PENDING','FAILED') AND retry_count < 5
  AND (next_retry_time IS NULL OR next_retry_time <= NOW());
SELECT ROW_COUNT() AS claim1_affected_rows;

-- 第二条条件 UPDATE（模拟并发认领者 2，同一 id）
UPDATE wms_outbound_stock_task
SET status='PROCESSING', owner='wms-outbound-service:18084:thread-2', lease_until=DATE_ADD(NOW(), INTERVAL 60 SECOND),
    retry_count = retry_count + 1, update_time=NOW()
WHERE id=@id AND status IN ('PENDING','FAILED') AND retry_count < 5
  AND (next_retry_time IS NULL OR next_retry_time <= NOW());
SELECT ROW_COUNT() AS claim2_affected_rows;

SELECT '--- 认领后快照 ---' AS step;
SELECT id, status, retry_count, owner, lease_until, next_retry_time
FROM wms_outbound_stock_task WHERE id = @id;

-- b 上限截断：retry_count 已达 max-retry(5) 的 FAILED 任务，再认领影响 0 行
INSERT INTO wms_outbound_stock_task
    (outbound_no, sku_code, location_code, batch_no, change_qty, request_id, status, last_error,
     retry_count, next_retry_time, create_time, update_time, owner, lease_until)
VALUES ('OUT-TEST-B', 'SKU-B', 'LOC-1', 'B1', -5,
        'OUT:OUT-TEST-B:SKU-B:LOC-1:B1', 'FAILED', 'simulated exhausted',
        5, NOW(), NOW(), NOW(), NULL, NULL);
SET @id2 = LAST_INSERT_ID();

UPDATE wms_outbound_stock_task
SET status='PROCESSING', owner='wms-outbound-service:18084:thread-3', lease_until=DATE_ADD(NOW(), INTERVAL 60 SECOND),
    retry_count = retry_count + 1, update_time=NOW()
WHERE id=@id2 AND status IN ('PENDING','FAILED') AND retry_count < 5
  AND (next_retry_time IS NULL OR next_retry_time <= NOW());
SELECT ROW_COUNT() AS claim_exhausted_affected_rows;

SELECT id, status, retry_count, owner FROM wms_outbound_stock_task WHERE id = @id2;
