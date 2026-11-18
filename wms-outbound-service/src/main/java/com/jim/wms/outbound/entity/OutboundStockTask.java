package com.jim.wms.outbound.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 出库扣库存补偿任务。
 *
 * <p>本地事务先落 PENDING 任务，再逐条 Feign 调库存服务扣减；远程结果回写任务状态，
 * FAILED 任务由定时任务按 next_retry_time 重试，避免出现「单已确认、库存未扣」的静默不一致。</p>
 *
 * 状态：PENDING / SUCCESS / FAILED / CANCELLED。
 */
@Data
@TableName("wms_outbound_stock_task")
public class OutboundStockTask implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String outboundNo;

    private String skuCode;

    private String locationCode;

    private String batchNo;

    /** 本次扣减数量（负数） */
    private BigDecimal changeQty;

    /** 幂等键（biz_key），与库存流水 biz_key 一致 */
    private String requestId;

    private String status;

    private String lastError;

    private Integer retryCount;

    private LocalDateTime nextRetryTime;

    /** 认领者：实例:端口:线程；终态回写清空 */
    private String owner;

    /** 认领租约到期时间；仅 owner 匹配才续租，终态回写清空 */
    private LocalDateTime leaseUntil;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
