package com.jim.wms.stocktake.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
@TableName("wms_stocktake_item")
public class StocktakeItem implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String stocktakeNo;

    private String warehouseCode;

    private String locationCode;

    private String skuCode;

    private String batchNo;

    /** 账面数量 */
    private BigDecimal bookQty;

    /** 实盘数量 */
    private BigDecimal actualQty;

    /** 差异 = 实盘 - 账面 */
    private BigDecimal diffQty;
}
