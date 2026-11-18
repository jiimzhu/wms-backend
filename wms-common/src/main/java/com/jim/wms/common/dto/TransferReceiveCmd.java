package com.jim.wms.common.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 调入仓收货完成，调拨单置 FINISHED。
 */
@Data
public class TransferReceiveCmd {

    @NotBlank
    private String transferNo;

    /** 收货备注，可空。 */
    private String remark;
}
