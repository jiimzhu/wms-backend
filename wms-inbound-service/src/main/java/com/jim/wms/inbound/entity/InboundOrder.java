package com.jim.wms.inbound.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 入库单：采购入库、调拨入库、退货入库共用
 */
@Data
@TableName("wms_inbound_order")
public class InboundOrder implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String inboundNo;

    /** PURCHASE / TRANSFER / RETURN */
    private String inboundType;

    /** ERP / CTM / OMS */
    private String sourceSystem;

    /** 外部单号（采购单、调拨单） */
    private String sourceNo;

    private String warehouseCode;

    /** CREATED / RECEIVING / RECEIVED / PUTAWAY / FINISHED / CANCELLED */
    private String status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 明细不落库，仅作传输载体 */
    @TableField(exist = false)
    private List<InboundOrderItem> items = new ArrayList<>();
}
