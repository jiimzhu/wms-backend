package com.jim.wms.integration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * CTM 下发的补货 / 调拨申请单。
 * 注意：CTM 已完成逻辑预占（Allocated），WMS 只做实物作业，不再做库存锁定。
 * 字段名与校验注解沿用旧单体 com.jim.wms.integration.dto.CtmReplenishOrderDTO，不得变更。
 */
@Data
public class CtmReplenishOrderDTO implements Serializable {

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
    public static class Item implements Serializable {

        @NotBlank(message = "skuCode 不能为空")
        private String skuCode;

        @NotNull(message = "qty 不能为空")
        private BigDecimal qty;

        private String batchNo;
    }
}
