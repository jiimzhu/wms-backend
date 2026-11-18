package com.jim.wms.stocktake.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("wms_stocktake_order")
public class StocktakeOrder implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String stocktakeNo;

    private String warehouseCode;

    /** FULL 全盘 / CIRCLE 循环盘点 / MOVE 动碰盘点 */
    private String stocktakeType;

    /** CREATED / COUNTING / COUNTED / ADJUSTED / CANCELLED */
    private String status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
