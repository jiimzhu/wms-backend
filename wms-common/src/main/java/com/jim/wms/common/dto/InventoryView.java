package com.jim.wms.common.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 库存台账视图，字段对齐旧实体 com.jim.wms.inventory.entity.Inventory
 */
@Data
public class InventoryView implements Serializable {

    private String warehouseCode;

    private String locationCode;

    private String skuCode;

    private String batchNo;

    private LocalDate expireDate;

    private String snCode;

    private BigDecimal qty;

    private BigDecimal freezeQty;

    /** 1 正常 2 冻结 3 残次 */
    private Integer stockStatus;
}
