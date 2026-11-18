package com.jim.wms.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 取消出库单指令，字段对齐旧 CtmReplenishController.CancelRequest
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CancelOutboundCmd implements Serializable {

    private String ctmOrderNo;

    private String wmsOutboundNo;
}
