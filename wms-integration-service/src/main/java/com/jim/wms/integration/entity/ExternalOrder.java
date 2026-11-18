package com.jim.wms.integration.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 外部单据登记表，用于幂等（CTM / OMS / ERP 单据号唯一）与状态回写
 */
@Data
@TableName("wms_external_order")
public class ExternalOrder implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** CTM / OMS / ERP */
    private String sourceSystem;

    private String externalOrderNo;

    /** WMS 出库单号（建单成功后回填） */
    private String outboundNo;

    /** REPLENISH / TRANSFER / SALE_OUT / PURCHASE_IN */
    private String businessType;

    private String warehouseCode;

    /** RECEIVED / PICKING / PARTIAL / FINISHED / CANCELLED / FAILED */
    private String status;

    /** 原始报文，便于排查 */
    private String rawJson;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
