package com.jim.wms.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * WMS 回调 CTM 单据状态指令，字段对齐旧 CtmCallbackClient 请求体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CtmCallbackCmd implements Serializable {

    private String orderNo;

    private String status;

    private String message;
}
