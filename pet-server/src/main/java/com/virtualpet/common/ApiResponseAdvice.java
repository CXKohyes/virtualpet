package com.virtualpet.common;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.time.Clock;
import java.time.Instant;

/**
 * 在写出响应体前给 {@link ApiResponse} 统一补上 {@code serverTime}（TECH_DESIGN 6.5）。
 *
 * <p>放在这里而不是各控制器里，是为了保证成功和失败两条路径的时间戳来源一致，
 * 并且全项目只有 {@link Clock} 一个时间入口。</p>
 */
@RestControllerAdvice
public class ApiResponseAdvice implements ResponseBodyAdvice<Object> {

    private final Clock clock;

    public ApiResponseAdvice(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof ApiResponse<?> apiResponse) {
            // 顺手把业务结果码交给访问日志（PRD 6.6）。这里是唯一一处
            // 能同时看到"响应信封"和"当前请求"的地方，错过就得再解析一遍响应体。
            rememberResultCode(apiResponse, request);
            return apiResponse.withServerTime(Instant.now(clock));
        }
        return body;
    }

    private void rememberResultCode(ApiResponse<?> apiResponse, ServerHttpRequest request) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            servletRequest.getServletRequest()
                    .setAttribute(RequestLoggingFilter.CODE_ATTRIBUTE, apiResponse.code());
        }
    }
}
