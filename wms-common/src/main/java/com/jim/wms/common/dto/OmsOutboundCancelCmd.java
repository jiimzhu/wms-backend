package com.jim.wms.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * OMS 取消出库单指令。{@code omsOrderNo} 与 {@code wmsOutboundNo} 不能同时为空，
 * 服务端校验返回 400；取消原因 {@code reason} 可空。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OmsOutboundCancelCmd implements Serializable {

    /** OMS 业务单号（与 wmsOutboundNo 二选一） */
    private String omsOrderNo;

    /** WMS 出库单号（与 omsOrderNo 二选一） */
    private String wmsOutboundNo;

    private String reason;
}
