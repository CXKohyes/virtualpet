package com.virtualpet.common;

/**
 * 业务异常：携带 {@link ErrorCode}，由 {@link GlobalExceptionHandler} 转成统一响应。
 *
 * <p>不返回 null 表示错误，也不用异常传递正常控制流以外的信息。</p>
 */
public class BusinessException extends RuntimeException {

    private final transient ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.message());
    }

    /** 用更具体的文案覆盖错误码的默认说明，例如「它现在不饿」。 */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
