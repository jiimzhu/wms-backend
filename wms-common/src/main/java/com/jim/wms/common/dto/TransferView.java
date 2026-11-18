package com.jim.wms.common.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 调拨单视图（内部接口返回）。
 */
@Data
public class TransferView {

    private String transferNo;
    private String fromWarehouseCode;
    private String toWarehouseCode;
    private String skuCode;
    private String batchNo;
    private BigDecimal qty;
    private String status;
}
