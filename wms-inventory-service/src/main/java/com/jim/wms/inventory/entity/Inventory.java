package com.jim.wms.inventory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 实物库存台账：仓库 + 库位 + SKU + 批次 + 效期 + 序列号 为唯一维度
 */
@Data
@TableName("wms_inventory")
public class Inventory implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

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

    @Version
    private Integer version;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
