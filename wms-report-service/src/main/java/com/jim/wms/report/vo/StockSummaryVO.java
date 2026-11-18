package com.jim.wms.report.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 仓库 + SKU 维度的库存汇总（结构沿用旧单体 com.jim.wms.report.vo.StockSummaryVO，字段不变）
 */
@Data
public class StockSummaryVO {

    private String warehouseCode;

    private String skuCode;

    private BigDecimal onHandQty;

    private BigDecimal freezeQty;

    private BigDecimal availableQty;
}
