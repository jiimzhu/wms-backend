package com.jim.wms.inventory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 库存流水：只新增，不修改不删除，用于追溯与 CTM 对账。
 * bizKey 即库存变动指令的 requestId（幂等键），同一 bizKey 重复请求不重复扣减。
 */
@Data
@TableName("wms_inventory_log")
public class InventoryLog implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String warehouseCode;

    private String locationCode;

    private String skuCode;

    private String batchNo;

    /** PURCHASE_IN / SALE_OUT / TRANSFER_IN / TRANSFER_OUT / STOCKTAKE_ADJ / RETURN_IN / FREEZE */
    private String businessType;

    private String businessNo;

    private BigDecimal changeQty;

    private BigDecimal beforeQty;

    private BigDecimal afterQty;

    private String remark;

    /** 幂等键（requestId），可空；空表示调用方未携带 requestId，不做幂等去重 */
    private String bizKey;

    private LocalDateTime createTime;
}
