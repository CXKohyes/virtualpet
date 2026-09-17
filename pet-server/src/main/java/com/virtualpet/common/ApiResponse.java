package com.virtualpet.common;

import java.time.Instant;

/**
 * 统一响应结构（TECH_DESIGN 5）。
 *
 * <pre>
 * {
 *   "code": "OK",
 *   "message": "success",
 *   "data": {},
 *   "serverTime": "2026-09-17T12:00:00Z"
 * }
 * </pre>
 *
 * <p>{@code serverTime} 由 {@link ApiResponseAdvice} 在写出响应前统一填充，
 * 控制器不需要自己传，避免各处调用 {@code Instant.now()}。</p>
 *
 * @param code       {@link ErrorCode} 的名称
 * @param message    给用户看的说明
 * @param data       业务数据，出错时为 {@code null}
 * @param serverTime 服务器当前时间（UTC）
 */
public record ApiResponse<T>(String code, String message, T data, Instant serverTime) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.OK.name(), ErrorCode.OK.message(), data, null);
    }

    public static ApiResponse<Void> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.name(), message, null, null);
    }

    /** 填充服务器时间；已经填过则原样返回。 */
    public ApiResponse<T> withServerTime(Instant now) {
        return serverTime == null ? new ApiResponse<>(code, message, data, now) : this;
    }
}
