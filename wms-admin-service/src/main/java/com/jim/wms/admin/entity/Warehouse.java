package com.jim.wms.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 仓库（DC），对应 CTM 的仓维度
 */
@Data
@TableName("wms_warehouse")
public class Warehouse implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String warehouseCode;

    private String warehouseName;

    /** 1 实体仓 2 虚拟仓 3 门店中转仓 */
    private Integer warehouseType;

    private Integer enable;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
