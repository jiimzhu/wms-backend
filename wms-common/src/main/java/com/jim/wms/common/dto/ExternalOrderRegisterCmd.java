package com.jim.wms.common.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 外部单据幂等登记指令，字段对齐旧实体 com.jim.wms.integration.entity.ExternalOrder
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExternalOrderRegisterCmd implements Serializable {

    /** CTM / OMS / ERP */
    @NotBlank
    private String sourceSystem;

    @NotBlank
    private String externalOrderNo;

    /** REPLENISH / TRANSFER / SALE_OUT / PURCHASE_IN */
    @NotBlank
    private String businessType;

    private String warehouseCode;

    /** 原始报文，便于排查 */
    private String rawJson;
}
