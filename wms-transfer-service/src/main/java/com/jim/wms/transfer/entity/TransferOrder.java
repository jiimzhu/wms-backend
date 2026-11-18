package com.jim.wms.transfer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 跨 DC 调拨单跟踪。
 * 状态机：CREATED -> IN_TRANSIT -> FINISHED。
 * 注意：本服务只维护调拨跟踪单；实物调出/调入由 outbound / inbound 流程经库存服务完成，
 * 本服务不直接调用库存变动（沿用旧单体 TransferServiceImpl 行为）。
 */
@Data
@TableName("wms_transfer_order")
public class TransferOrder implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String transferNo;

    private String sourceSystem;

    private String sourceNo;

    private String outboundNo;

    private String fromWarehouseCode;

    private String toWarehouseCode;

    private String skuCode;

    private String batchNo;

    private BigDecimal qty;

    private String status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
