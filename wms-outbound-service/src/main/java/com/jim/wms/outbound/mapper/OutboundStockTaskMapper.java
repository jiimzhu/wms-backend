package com.jim.wms.outbound.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jim.wms.outbound.entity.OutboundStockTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OutboundStockTaskMapper extends BaseMapper<OutboundStockTask> {

    /**
     * 候选任务：PENDING/FAILED 且到期未超上限，或 PROCESSING 租约过期需回收（规格第 4 节）。
     * 尽量走 idx_status_retry / idx_status_lease。
     */
    @Select("SELECT * FROM wms_outbound_stock_task "
            + "WHERE (status IN ('PENDING','FAILED') AND retry_count < #{maxRetry} "
            + "       AND (next_retry_time IS NULL OR next_retry_time <= #{now})) "
            + "   OR (status = 'PROCESSING' AND lease_until IS NOT NULL AND lease_until < #{now}) "
            + "ORDER BY id ASC")
    List<OutboundStockTask> selectCandidates(@Param("now") LocalDateTime now,
                                             @Param("maxRetry") int maxRetry);

    /**
     * 认领新任务（PENDING/FAILED）：单条条件 UPDATE，影响 1 行才算抢到；认领时 retry_count + 1。
     */
    @Update("UPDATE wms_outbound_stock_task "
            + "SET status='PROCESSING', owner=#{owner}, lease_until=#{leaseUntil}, "
            + "    retry_count = retry_count + 1, update_time=#{now} "
            + "WHERE id=#{id} AND status IN ('PENDING','FAILED') AND retry_count < #{maxRetry} "
            + "  AND (next_retry_time IS NULL OR next_retry_time <= #{now})")
    int claimNew(@Param("id") Long id,
                 @Param("owner") String owner,
                 @Param("leaseUntil") LocalDateTime leaseUntil,
                 @Param("now") LocalDateTime now,
                 @Param("maxRetry") int maxRetry);

    /**
     * confirm 首次同步认领：仅 PENDING 且 retry_count=0 的全新任务，不判 next_retry_time 到期；
     * 条件 UPDATE 影响 1 行才算抢到，认领时 retry_count + 1，写 owner/lease_until。
     * 仅 confirm 同步循环使用，扫描器仍走 {@link #claimNew} / {@link #claimExpired}。
     */
    @Update("UPDATE wms_outbound_stock_task "
            + "SET status='PROCESSING', owner=#{owner}, lease_until=#{leaseUntil}, "
            + "    retry_count = retry_count + 1, update_time=#{now} "
            + "WHERE id=#{id} AND status='PENDING' AND retry_count=0")
    int claimFirstAttempt(@Param("id") Long id,
                          @Param("owner") String owner,
                          @Param("leaseUntil") LocalDateTime leaseUntil,
                          @Param("now") LocalDateTime now);

    /**
     * 回收过期 PROCESSING 认领：租约到期才允许被其它实例/线程重新认领，owner 变更，retry_count + 1。
     */
    @Update("UPDATE wms_outbound_stock_task "
            + "SET status='PROCESSING', owner=#{owner}, lease_until=#{leaseUntil}, "
            + "    retry_count = retry_count + 1, update_time=#{now} "
            + "WHERE id=#{id} AND status='PROCESSING' AND lease_until < #{now} "
            + "  AND retry_count < #{maxRetry}")
    int claimExpired(@Param("id") Long id,
                     @Param("owner") String owner,
                     @Param("leaseUntil") LocalDateTime leaseUntil,
                     @Param("now") LocalDateTime now,
                     @Param("maxRetry") int maxRetry);

    /**
     * 续租：仅当前 owner 匹配才续，影响 0 行说明已被回收/终态，不得继续操作。
     */
    @Update("UPDATE wms_outbound_stock_task "
            + "SET lease_until=#{leaseUntil}, update_time=#{now} "
            + "WHERE id=#{id} AND owner=#{owner}")
    int renewLease(@Param("id") Long id,
                   @Param("owner") String owner,
                   @Param("leaseUntil") LocalDateTime leaseUntil,
                   @Param("now") LocalDateTime now);

    /**
     * 终态 SUCCESS 回写：清空 owner/lease_until/last_error/next_retry_time。
     */
    @Update("UPDATE wms_outbound_stock_task "
            + "SET status='SUCCESS', owner=NULL, lease_until=NULL, last_error=NULL, "
            + "    next_retry_time=NULL, update_time=#{now} "
            + "WHERE id=#{id}")
    int markSuccessTerminal(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * 终态 FAILED 回写：清空 owner/lease_until，记录 last_error、累计 retry_count、顺延 next_retry_time。
     */
    @Update("UPDATE wms_outbound_stock_task "
            + "SET status='FAILED', owner=NULL, lease_until=NULL, last_error=#{lastError}, "
            + "    retry_count=#{retryCount}, next_retry_time=#{nextRetryTime}, update_time=#{now} "
            + "WHERE id=#{id}")
    int markFailedTerminal(@Param("id") Long id,
                           @Param("lastError") String lastError,
                           @Param("retryCount") int retryCount,
                           @Param("nextRetryTime") LocalDateTime nextRetryTime,
                           @Param("now") LocalDateTime now);

    /**
     * 终态 CANCELLED 回写：清空 owner/lease_until。
     */
    @Update("UPDATE wms_outbound_stock_task "
            + "SET status='CANCELLED', owner=NULL, lease_until=NULL, update_time=#{now} "
            + "WHERE id=#{id}")
    int markCancelledTerminal(@Param("id") Long id, @Param("now") LocalDateTime now);
}
