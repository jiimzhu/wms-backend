package com.jim.wms.common.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * OMS 销售出库建单指令（内部 Feign 契约）。
 * 订单类型不接受外部传入，服务端固定落 {@code SALE_OUT}；
 * {@code omsOrderNo} 为 WMS 幂等键。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OmsOutboundOrderCmd implements Serializable {

    /** OMS 业务单号，WMS 幂等键 */
    @NotBlank(message = "omsOrderNo 不能为空")
    private String omsOrderNo;

    @NotBlank(message = "warehouseCode 不能为空")
    private String warehouseCode;

    /** 备注，可空 */
    private String remark;

    @Valid
    @NotNull(message = "明细不能为空")
    private List<Item> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item implements Serializable {

        @NotBlank(message = "skuCode 不能为空")
        private String skuCode;

        @NotBlank(message = "locationCode 不能为空")
        private String locationCode;

        private String batchNo;

        @NotNull(message = "qty 不能为空")
        @DecimalMin(value = "0.000001", message = "qty 必须大于 0")
        private BigDecimal qty;
    }
}
