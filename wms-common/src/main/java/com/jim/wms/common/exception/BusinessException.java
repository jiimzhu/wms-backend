package com.jim.wms.common.exception;

import lombok.Getter;

/**
 * 业务异常（可预期，不打印堆栈）
 */
@Getter
public class BusinessException extends RuntimeException {

    private final Integer code;

    public BusinessException(String message) {
        super(message);
        this.code = 500;
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }
}
