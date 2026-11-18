package com.jim.wms.common.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 商品主数据视图，字段对齐旧实体 com.jim.wms.admin.entity.SkuItem
 */
@Data
public class SkuView implements Serializable {

    private String skuCode;

    private String skuName;

    /** 基本单位 */
    private String unit;

    /** 0 不管批次 1 管批次 */
    private Integer batchManage;

    /** 0 不管效期 1 管效期 */
    private Integer expireManage;

    /** 0 不管序列号 1 管序列号 */
    private Integer snManage;

    private Integer enable;
}
