package com.jim.wms.common.dto;

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
 * CTM 补货 / 调拨申请单（内部 Feign 契约），字段对齐旧 CtmReplenishOrderDTO。
 * CTM 已完成逻辑预占，WMS 只做实物作业，不再做库存锁定。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReplenishOrderCmd implements Serializable {

    /** CTM 业务单号，WMS 幂等键 */
    @NotBlank(message = "ctmOrderNo 不能为空")
    private String ctmOrderNo;

    /** 单据类型：REPLENISH 门店补货 / TRANSFER DC 间调拨 */
    @NotBlank(message = "orderType 不能为空")
    private String orderType;

    @NotBlank(message = "sourceWarehouseCode 不能为空")
    private String sourceWarehouseCode;

    /** 调拨时的调入仓，补货时可为空 */
    private String targetWarehouseCode;

    private String priority;

    @NotNull(message = "明细不能为空")
    private List<Item> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item implements Serializable {

        @NotBlank(message = "skuCode 不能为空")
        private String skuCode;

        @NotNull(message = "qty 不能为空")
        private BigDecimal qty;

        private String batchNo;
    }
}
