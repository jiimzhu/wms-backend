package com.jim.wms.outbound.entity;

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
 * 出库单。orderType：
 * SALE 商城销售出库（来源 OMS）
 * REPLENISH 门店补货出库（来源 CTM）
 * TRANSFER_OUT 跨 DC 调拨出库（来源 CTM）
 */
@Data
@TableName("wms_outbound_order")
public class OutboundOrder implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String outboundNo;

    private String orderType;

    private String sourceSystem;

    private String sourceNo;

    private String warehouseCode;

    /** 调拨时的调入仓 */
    private String targetWarehouseCode;

    /** CREATED / PICKING / CONFIRMING / PICKED / FINISHED / CANCELLED / CONFIRM_FAILED */
    private String status;

    /** 单据级最近一次失败原因（如调拨在途 404），供补扫排查 */
    private String lastError;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableField(exist = false)
    private List<OutboundOrderItem> items = new ArrayList<>();
}
