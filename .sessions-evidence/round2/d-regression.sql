USE jim_wms_outbound;

-- ============ d1 认领唯一性：同一 id 两条条件 UPDATE，影响行数 1 与 0 ============
INSERT INTO wms_outbound_stock_task (outbound_no,sku_code,location_code,batch_no,change_qty,request_id,status,retry_count,next_retry_time,create_time,update_time)
VALUES ('REGRESS-D','SKU-D1','LOC-D1','B-D1',-1.000,'OUT:REGRESS-D:SKU-D1:LOC-D1:B-D1','PENDING',0,NOW(),NOW(),NOW());
SET @t1 = LAST_INSERT_ID();

UPDATE wms_outbound_stock_task
SET status='PROCESSING', owner='reg-owner-1', lease_until=DATE_ADD(NOW(), INTERVAL 60 SECOND),
    retry_count = retry_count + 1, update_time=NOW()
WHERE id=@t1 AND status IN ('PENDING','FAILED') AND retry_count < 5
  AND (next_retry_time IS NULL OR next_retry_time <= NOW());
SELECT ROW_COUNT() AS d1_first_claim_rows;

UPDATE wms_outbound_stock_task
SET status='PROCESSING', owner='reg-owner-1', lease_until=DATE_ADD(NOW(), INTERVAL 60 SECOND),
    retry_count = retry_count + 1, update_time=NOW()
WHERE id=@t1 AND status IN ('PENDING','FAILED') AND retry_count < 5
  AND (next_retry_time IS NULL OR next_retry_time <= NOW());
SELECT ROW_COUNT() AS d1_second_claim_rows;

SELECT id,status,retry_count,owner,lease_until FROM wms_outbound_stock_task WHERE id=@t1;

-- ============ d2 retry_count 认领自增（上一步 0->1）与 5 次上限截断 ============
UPDATE wms_outbound_stock_task SET retry_count=5, status='FAILED', next_retry_time=NOW() WHERE id=@t1;
UPDATE wms_outbound_stock_task
SET status='PROCESSING', owner='reg-owner-1', lease_until=DATE_ADD(NOW(), INTERVAL 60 SECOND),
    retry_count = retry_count + 1, update_time=NOW()
WHERE id=@t1 AND status IN ('PENDING','FAILED') AND retry_count < 5
  AND (next_retry_time IS NULL OR next_retry_time <= NOW());
SELECT ROW_COUNT() AS d2_at_max_retry_claim_rows;
SELECT id,status,retry_count FROM wms_outbound_stock_task WHERE id=@t1;

-- ============ d3 租约过期回收：过期可被重新认领，未过期不被抢 ============
INSERT INTO wms_outbound_stock_task (outbound_no,sku_code,location_code,batch_no,change_qty,request_id,status,retry_count,owner,lease_until,next_retry_time,create_time,update_time)
VALUES ('REGRESS-D','SKU-D2','LOC-D2','B-D2',-1.000,'OUT:REGRESS-D:SKU-D2:LOC-D2:B-D2','PROCESSING',1,'old-owner',DATE_SUB(NOW(), INTERVAL 10 SECOND),NULL,NOW(),NOW());
SET @t2 = LAST_INSERT_ID();
INSERT INTO wms_outbound_stock_task (outbound_no,sku_code,location_code,batch_no,change_qty,request_id,status,retry_count,owner,lease_until,next_retry_time,create_time,update_time)
VALUES ('REGRESS-D','SKU-D3','LOC-D3','B-D3',-1.000,'OUT:REGRESS-D:SKU-D3:LOC-D3:B-D3','PROCESSING',1,'old-owner',DATE_ADD(NOW(), INTERVAL 60 SECOND),NULL,NOW(),NOW());
SET @t3 = LAST_INSERT_ID();

UPDATE wms_outbound_stock_task
SET status='PROCESSING', owner='reg-owner-2', lease_until=DATE_ADD(NOW(), INTERVAL 60 SECOND),
    retry_count = retry_count + 1, update_time=NOW()
WHERE id=@t2 AND status='PROCESSING' AND lease_until < NOW() AND retry_count < 5;
SELECT ROW_COUNT() AS d3_expired_reclaim_rows;

UPDATE wms_outbound_stock_task
SET status='PROCESSING', owner='reg-owner-2', lease_until=DATE_ADD(NOW(), INTERVAL 60 SECOND),
    retry_count = retry_count + 1, update_time=NOW()
WHERE id=@t3 AND status='PROCESSING' AND lease_until < NOW() AND retry_count < 5;
SELECT ROW_COUNT() AS d3_healthy_not_stolen_rows;

SELECT id,status,owner,retry_count,lease_until FROM wms_outbound_stock_task WHERE id IN (@t2,@t3);

-- ============ d4 SUCCESS 终态回写清空 owner/lease_until/last_error/next_retry_time ============
UPDATE wms_outbound_stock_task
SET status='SUCCESS', owner=NULL, lease_until=NULL, last_error=NULL,
    next_retry_time=NULL, update_time=NOW()
WHERE id=@t2;
SELECT id,status,owner,lease_until,last_error,next_retry_time FROM wms_outbound_stock_task WHERE id=@t2;