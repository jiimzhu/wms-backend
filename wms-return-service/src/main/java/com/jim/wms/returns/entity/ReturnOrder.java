package com.jim.wms.returns.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退货单（逆向）：来源 OMS 售后或门店退货
 */
@Data
@TableName("wms_return_order")
public class ReturnOrder implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String returnNo;

    private String sourceSystem;

    private String sourceNo;

    private String warehouseCode;

    private String skuCode;

    private String batchNo;

    private BigDecimal qty;

    /** SALEABLE 可售回流 / DEFECT 残次 / SCRAP 报废 */
    private String disposition;

    /** CREATED / INSPECTED / IN_STOCK / SCRAPPED */
    private String status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
