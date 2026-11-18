package com.jim.wms.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * WMS 主动通知 OMS 单据状态指令，供 outbound → integration → OMS 回调链路使用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OmsStatusNotifyCmd implements Serializable {

    /** OMS 业务单号 */
    private String omsOrderNo;

    /** WMS 出库单号 */
    private String orderNo;

    /** 单据状态 */
    private String status;

    private String message;
}
