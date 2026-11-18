package com.jim.wms.common.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 库位视图，字段对齐旧实体 com.jim.wms.admin.entity.WarehouseLocation
 */
@Data
public class LocationView implements Serializable {

    private String warehouseCode;

    private String areaCode;

    private String locationCode;

    /** 1 收货位 2 存储位 3 拣货位 4 不合格位 5 发货位 */
    private Integer locationType;

    private BigDecimal capacity;

    private Integer enable;
}
