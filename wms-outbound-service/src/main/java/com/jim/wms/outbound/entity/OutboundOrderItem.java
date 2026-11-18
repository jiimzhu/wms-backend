package com.jim.wms.outbound.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
@TableName("wms_outbound_item")
public class OutboundOrderItem implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String outboundNo;

    private String skuCode;

    private String batchNo;

    private BigDecimal planQty;

    private BigDecimal pickedQty;

    private String locationCode;
}
