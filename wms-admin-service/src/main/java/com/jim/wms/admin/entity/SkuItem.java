package com.jim.wms.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;

/**
 * 商品主数据：是否批次/效期/序列号管理，直接决定库存台账的维度
 */
@Data
@TableName("wms_sku")
public class SkuItem implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

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
