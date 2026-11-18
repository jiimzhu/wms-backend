package com.jim.wms.inbound.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 入库单明细
 */
@Data
@TableName("wms_inbound_item")
public class InboundOrderItem implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String inboundNo;

    private String skuCode;

    private String batchNo;

    /** 预期到货数量 */
    private BigDecimal expectQty;

    /** 实收数量 */
    private BigDecimal receivedQty;

    /** 上架数量 */
    private BigDecimal putawayQty;

    /** 指定 / 实际库位 */
    private String locationCode;

    /** QC 状态：PENDING / PASS / REJECT */
    private String qcStatus;
}
