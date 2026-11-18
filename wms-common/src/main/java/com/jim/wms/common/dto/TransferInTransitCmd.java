package com.jim.wms.common.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 出库确认成功后通知调拨服务「调拨单已出库、进入在途」。
 */
@Data
public class TransferInTransitCmd {

    /** 调拨单号；优先按 transfer_no 匹配，其次按 source_no 匹配。 */
    @NotBlank
    private String transferNo;

    /** 触发出库单号，用于追溯与幂等排查。 */
    private String outboundNo;
}
