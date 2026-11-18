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
 * 冻结 / 解冻指令
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FreezeCmd implements Serializable {

    @NotBlank
    private String warehouseCode;

    @NotBlank
    private String locationCode;

    @NotBlank
    private String skuCode;

    private String batchNo;

    @NotNull
    private BigDecimal qty;
}
