package com.virtualpet.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求级访问日志（PRD 6.6）。
 *
 * <p>每个请求记录 <b>requestId、路径、耗时和结果码</b>。requestId 同时写进
 * MDC，所以同一次请求里所有日志行都会带上它，排查问题时能把一条链串起来。</p>
 *
 * <p>requestId 优先用调用方传的 {@code X-Request-Id}，没有才自己生成，
 * 并且无论哪种情况都回写到响应头 —— 前端和运维提工单时报这个 ID 就能定位。</p>
 *
 * <p>业务结果码（{@code OK} / {@code PET_NOT_FOUND} …）由
 * {@link ApiResponseAdvice} 在写响应体时塞进请求属性；拿不到就退回 HTTP 状态码，
 * 所以静态资源、actuator 这些没有信封的请求也有日志。</p>
 *
 * <p>顺序设为最高：过滤器要在鉴权拦截器之前开始计时，不然 401 的耗时会被算漏。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    /** 请求属性名：本次请求的业务结果码。{@link ApiResponseAdvice} 负责填。 */
    public static final String CODE_ATTRIBUTE = "virtualpet.resultCode";

    /** 请求/响应头里的请求标识。 */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** MDC 键名，和 application.yml 里的日志格式对应。 */
    public static final String REQUEST_ID_MDC_KEY = "requestId";

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    /** 调用方传的 requestId 最长留这么长，避免被塞超长字符串撑爆日志。 */
    private static final int MAX_REQUEST_ID_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        long startedAt = System.nanoTime();

        MDC.put(REQUEST_ID_MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        try {
            chain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.info("{} {} -> {} {} ({}ms)",
                    request.getMethod(), request.getRequestURI(),
                    response.getStatus(), resultCode(request, response), durationMs);
            MDC.remove(REQUEST_ID_MDC_KEY);
        }
    }

    /**
     * 取调用方给的 requestId，没有或不可用就生成一个。
     *
     * <p>只保留可打印字符并截断：这个值会进日志，不能让它带换行符之类的控制字符。</p>
     */
    private String resolveRequestId(HttpServletRequest request) {
        String supplied = request.getHeader(REQUEST_ID_HEADER);
        if (supplied == null || supplied.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String cleaned = supplied.replaceAll("[^A-Za-z0-9._-]", "");
        if (cleaned.isEmpty()) {
            return UUID.randomUUID().toString();
        }
        return cleaned.length() > MAX_REQUEST_ID_LENGTH
                ? cleaned.substring(0, MAX_REQUEST_ID_LENGTH)
                : cleaned;
    }

    /** 业务结果码优先，没有就用 HTTP 状态码。 */
    private String resultCode(HttpServletRequest request, HttpServletResponse response) {
        Object code = request.getAttribute(CODE_ATTRIBUTE);
        return code instanceof String text ? text : "HTTP_" + response.getStatus();
    }
}
