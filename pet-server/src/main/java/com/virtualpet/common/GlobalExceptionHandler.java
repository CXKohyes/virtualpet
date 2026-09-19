package com.virtualpet.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 把异常统一转成 {@link ApiResponse} 错误响应。
 *
 * <p>日志不输出令牌、数据库密码和完整设备标识（AGENTS.md 5.2）。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException exception) {
        ErrorCode errorCode = exception.errorCode();
        return ResponseEntity.status(errorCode.httpStatus())
                .body(ApiResponse.error(errorCode, exception.getMessage()));
    }

    /** {@code @Valid} 校验失败：取第一条字段错误作为提示。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage() == null ? ErrorCode.INVALID_REQUEST.message()
                        : error.getDefaultMessage())
                .orElse(ErrorCode.INVALID_REQUEST.message());
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.httpStatus())
                .body(ApiResponse.error(ErrorCode.INVALID_REQUEST, message));
    }

    /** 请求体缺失、JSON 格式错误或枚举值非法。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException exception) {
        log.debug("请求体无法解析: {}", exception.getMessage());
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.httpStatus())
                .body(ApiResponse.error(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.message()));
    }

    /**
     * 路径没有对应的接口。
     *
     * <p>必须单独处理：Spring Boot 3.2 起，映射不到的路径会抛
     * {@link NoResourceFoundException}，不拦住的话会被下面的兜底处理器变成 500。
     * dev 接口在非 dev profile 下不存在，就是走这条路径返回 404 的。</p>
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException exception) {
        log.debug("接口不存在: {}", exception.getMessage());
        return ResponseEntity.status(ErrorCode.NOT_FOUND.httpStatus())
                .body(ApiResponse.error(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.message()));
    }

    /**
     * 路径或查询参数的类型转不过去。
     *
     * <p>典型场景：{@code DELETE /api/v1/pets/me} 里的 {@code "me"} 往 {@code Long}
     * 上转。加了 {@code /pets/{petId}} 这类路由之后，这条路径随时会被走到 ——
     * 客户端把 URL 写错或用了旧版本的地址都会撞上。</p>
     *
     * <p>不单独拦住的话它会落到下面的兜底处理器变成 <b>500</b>：客户端写错了 URL，
     * 服务端却报「服务器开小差了」，排查方向会被整个带偏。
     * 这和 {@link #handleNoResource} 要单独处理是同一类问题。</p>
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        log.debug("参数类型不匹配: {} = {}", exception.getName(), exception.getValue());
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.httpStatus())
                .body(ApiResponse.error(ErrorCode.INVALID_REQUEST, ErrorCode.INVALID_REQUEST.message()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception) {
        log.error("未预期的服务端错误", exception);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.message()));
    }
}
