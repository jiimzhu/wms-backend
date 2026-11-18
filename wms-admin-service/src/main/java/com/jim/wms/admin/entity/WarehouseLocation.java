package com.jim.wms.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 库位：库存最小存放单元，库存台账按库位维度存放
 */
@Data
@TableName("wms_location")
public class WarehouseLocation implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String warehouseCode;

    private String areaCode;

    private String locationCode;

    /** 1 收货位 2 存储位 3 拣货位 4 不合格位 5 发货位 */
    private Integer locationType;

    /** 库位容量，用于上架策略 */
    private BigDecimal capacity;

    private Integer enable;
}
