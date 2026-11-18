package com.jim.wms.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

/**
 * 库区：收货区、存储区、拣货区、不合格区、发货区等
 */
@Data
@TableName("wms_area")
public class WarehouseArea implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String warehouseCode;

    private String areaCode;

    private String areaName;

    /** 1 收货 2 存储 3 拣货 4 不合格 5 发货 6 退货 */
    private Integer areaType;
}
