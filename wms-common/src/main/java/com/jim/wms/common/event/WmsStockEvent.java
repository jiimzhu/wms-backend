package com.jim.wms.common.event;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 库存变动事件，Kafka 推送给 CTM，用于维护 CTM 侧的逻辑镜像库存。
 * WMS 是实物库存真值来源，CTM 只做镜像。
 * 字段与旧单体 com.jim.wms.integration.event.WmsStockEvent 完全一致。
 */
@Data
public class WmsStockEvent implements Serializable {

    private String eventId;

    /** INBOUND / OUTBOUND / TRANSFER_IN / TRANSFER_OUT / STOCKTAKE_ADJ / RETURN_IN */
    private String eventType;

    private String warehouseCode;

    private String locationCode;

    private String skuCode;

    private String batchNo;

    /** 业务单据号：采购入库单、销售出库单、CTM 单号等 */
    private String businessNo;

    private BigDecimal changeQty;

    private BigDecimal beforeQty;

    private BigDecimal afterQty;

    private LocalDateTime eventTime;
}
