package com.virtualpet.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 接口限流（PRD 6.2 的「速率限制」）。
 *
 * <p>公网部署后 {@code POST /api/v1/session} 是**免鉴权**的，任何人每发一次请求
 * 都会往 {@code players} 表里插一行。没有节流的话，扫到地址的人刷一晚上就能把
 * 磁盘和连接数吃满。这个过滤器就是堵这个口子。</p>
 *
 * <h2>分两档</h2>
 * <ul>
 *   <li><b>建会话</b>——严格档。唯一一个不需要令牌就能写库的接口。</li>
 *   <li><b>其余 /api/v1</b>——宽松档。正常游玩一次操作的请求量很小，
 *       这档主要是拦住脚本化的洪水，不影响真人。</li>
 * </ul>
 *
 * <h2>客户端标识</h2>
 * <p>默认用 {@code request.getRemoteAddr()}。<b>只有在确认前面有反向代理时</b>
 * 才打开 {@code trust-forwarded-header}，这时才读 {@code X-Real-IP}——
 * 因为这个头是客户端可伪造的，直连场景下信它等于把限流开关交给攻击者。</p>
 *
 * <p>本项目生产用的是 nginx，配置里写了 {@code proxy_set_header X-Real-IP $remote_addr}
 * （覆盖而非追加，所以伪造不了），而且后端只监听 127.0.0.1，
 * 公网进不来 —— 这两个前提同时成立时打开才是安全的。</p>
 *
 * <p>顺序排在 {@link RequestLoggingFilter} 之后，这样被限掉的请求同样会进访问日志，
 * 日志里能看到 429 和 {@code RATE_LIMITED}，便于判断是不是被打。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** 限流只覆盖业务接口；actuator 和静态资源不在此列。 */
    private static final String API_PREFIX = "/api/v1/";

    /** 建立会话的路径，单独一档。 */
    private static final String SESSION_PATH = "/api/v1/session";

    /** 客户端标识最长留这么长，避免被塞超长字符串当 map 键。 */
    private static final int MAX_KEY_LENGTH = 64;

    private final boolean enabled;
    private final boolean trustForwardedHeader;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private final RateLimiter sessionLimiter;
    private final RateLimiter generalLimiter;

    public RateLimitFilter(
            @Value("${app.rate-limit.enabled:true}") boolean enabled,
            @Value("${app.rate-limit.trust-forwarded-header:false}") boolean trustForwardedHeader,
            @Value("${app.rate-limit.session-per-minute:60}") int sessionPerMinute,
            @Value("${app.rate-limit.general-per-minute:300}") int generalPerMinute,
            ObjectMapper objectMapper,
            Clock clock) {
        this.enabled = enabled;
        this.trustForwardedHeader = trustForwardedHeader;
        this.objectMapper = objectMapper;
        this.clock = clock;

        Duration window = Duration.ofMinutes(1);
        // 阈值给 4096：普通小站的 key 数（真实客户端 IP）远到不了这里，
        // 清扫基本不会触发；真被刷时才启动回收。
        this.sessionLimiter = new RateLimiter(sessionPerMinute, window, 4096);
        this.generalLimiter = new RateLimiter(generalPerMinute, window, 4096);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!enabled || !request.getRequestURI().startsWith(API_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        RateLimiter limiter = isSessionCreation(request) ? sessionLimiter : generalLimiter;
        String key = clientKey(request);
        long nowNanos = System.nanoTime();

        if (limiter.tryAcquire(key, nowNanos)) {
            chain.doFilter(request, response);
            return;
        }

        Duration retryAfter = limiter.retryAfter(key, nowNanos);
        log.warn("限流命中：{} {} 来自 {}，{} 秒后重试",
                request.getMethod(), request.getRequestURI(), key, retryAfter.toSeconds());
        reject(response, retryAfter, request);
    }

    private boolean isSessionCreation(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && SESSION_PATH.equals(request.getRequestURI());
    }

    /**
     * 取客户端标识。
     *
     * <p>X-Real-IP 只有在 {@code trust-forwarded-header} 打开时才采信 —— 它是
     * 客户端可伪造的头，无条件相信等于给攻击者留了「每次换个假 IP 就绕过限流」的后门。</p>
     */
    private String clientKey(HttpServletRequest request) {
        if (trustForwardedHeader) {
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) {
                return bound(realIp.trim());
            }
        }
        return bound(request.getRemoteAddr());
    }

    /** 截断并滤掉可疑字符：这个值会当 map 键，也会进日志。 */
    private String bound(String value) {
        if (value == null) {
            return "unknown";
        }
        String cleaned = value.replaceAll("[^A-Za-z0-9.:_-]", "");
        if (cleaned.isEmpty()) {
            return "unknown";
        }
        return cleaned.length() > MAX_KEY_LENGTH ? cleaned.substring(0, MAX_KEY_LENGTH) : cleaned;
    }

    /**
     * 写 429。
     *
     * <p>这里跑在 DispatcherServlet 之前，{@link ApiResponseAdvice} 管不到，
     * 所以响应信封得自己拼 —— 用同一套 {@link ApiResponse}，前端才能按同一套规则解析。
     * {@code serverTime} 照样来自注入的 {@link Clock}。</p>
     */
    private void reject(HttpServletResponse response, Duration retryAfter,
                        HttpServletRequest request) throws IOException {
        request.setAttribute(RequestLoggingFilter.CODE_ATTRIBUTE, ErrorCode.RATE_LIMITED.name());

        response.setStatus(ErrorCode.RATE_LIMITED.httpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(retryAfter.toSeconds(), 1)));

        ApiResponse<Void> body = ApiResponse
                .error(ErrorCode.RATE_LIMITED, ErrorCode.RATE_LIMITED.message())
                .withServerTime(Instant.now(clock));
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
