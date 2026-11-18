-- d 终态回写清空（SQL 形态与 OutboundStockTaskMapper#markSuccessTerminal 一致）
USE jim_wms_outbound;

-- 先给 id=9 残留上一轮失败文案与 next_retry_time，模拟非干净状态
UPDATE wms_outbound_stock_task
SET last_error='prior failure text', next_retry_time=NOW(), owner='wms-outbound-service:18084:thread-1',
    lease_until=DATE_ADD(NOW(), INTERVAL 60 SECOND)
WHERE id=9;

SELECT '--- SUCCESS 回写前 ---' AS step;
SELECT id, status, owner, lease_until, last_error, next_retry_time FROM wms_outbound_stock_task WHERE id=9;

UPDATE wms_outbound_stock_task
SET status='SUCCESS', owner=NULL, lease_until=NULL, last_error=NULL,
    next_retry_time=NULL, update_time=NOW()
WHERE id=9;
SELECT ROW_COUNT() AS success_writeback_rows;

SELECT '--- SUCCESS 回写后（owner/lease_until/last_error/next_retry_time 均应为 NULL）---' AS step;
SELECT id, status, owner, lease_until, last_error, next_retry_time FROM wms_outbound_stock_task WHERE id=9;
