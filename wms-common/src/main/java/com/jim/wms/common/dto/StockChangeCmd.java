package com.jim.wms.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 库存变动指令，所有入库/出库/调拨/盘点最终都收敛到这里扣加库存。
 * requestId 为幂等键，库存侧按 biz_key 去重，重复请求不重复扣减。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockChangeCmd implements Serializable {

    @NotBlank
    private String warehouseCode;

    @NotBlank
    private String locationCode;

    @NotBlank
    private String skuCode;

    private String batchNo;

    /** 正数增加，负数减少 */
    @NotNull
    private BigDecimal changeQty;

    /** 业务类型：PURCHASE_IN / SALE_OUT / TRANSFER_IN / TRANSFER_OUT / STOCKTAKE_ADJ / RETURN_IN */
    @NotBlank
    private String businessType;

    @NotBlank
    private String businessNo;

    private String remark;

    /** 幂等键（biz_key），同一 requestId 重复请求直接返回当前库存，不重复扣减 */
    private String requestId;
}
