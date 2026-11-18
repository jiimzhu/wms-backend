package com.jim.wms.common.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 仓库视图，字段对齐旧实体 com.jim.wms.admin.entity.Warehouse
 */
@Data
public class WarehouseView implements Serializable {

    private String warehouseCode;

    private String warehouseName;

    /** 1 实体仓 2 虚拟仓 3 门店中转仓 */
    private Integer warehouseType;

    private Integer enable;
}
