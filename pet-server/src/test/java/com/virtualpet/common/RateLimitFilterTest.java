package com.virtualpet.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 限流过滤器的端到端行为（PRD 6.2）。
 *
 * <p>测试档默认是关掉限流的（见 {@code application-test.yml}：各测试类共用同一个
 * 被缓存的上下文，限流桶会跨用例累积）。这里用 {@link TestPropertySource}
 * 临时打开并把阈值压到 2，专门验 429 这条路。</p>
 *
 * <p><b>每个用例用自己的来源 IP。</b>因为上下文是共享的，限流桶也是共享的 ——
 * 不换 IP 的话前一个用例把令牌耗光，后一个用例就会莫名其妙地失败，
 * 而且失败原因看起来和它测的东西毫无关系。换 IP 同时也顺带验了「限流按来源分桶」。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.session-per-minute=2",
        "app.rate-limit.general-per-minute=2",
})
class RateLimitFilterTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** 把请求伪装成来自指定 IP，让每个用例拿到独立的限流桶。 */
    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private MvcResult createSession(String ip, String deviceId) throws Exception {
        return mockMvc.perform(post("/api/v1/session")
                        .with(from(ip))
                        .contentType(JSON)
                        .content("{\"deviceId\":\"" + deviceId + "\"}"))
                .andReturn();
    }

    @Test
    @DisplayName("建会话超过阈值后返回 429，并且仍然是统一的响应信封")
    void sessionCreationIsRateLimited() throws Exception {
        String ip = "203.0.113.10";

        // 阈值是 2，前两次正常
        assertThat(createSession(ip, "rate-a").getResponse().getStatus()).isEqualTo(200);
        assertThat(createSession(ip, "rate-b").getResponse().getStatus()).isEqualTo(200);

        MvcResult blocked = createSession(ip, "rate-c");

        assertThat(blocked.getResponse().getStatus())
                .as("第三次应当被限流")
                .isEqualTo(429);

        // 前端按同一套规则解析，所以信封字段一个都不能少
        JsonNode body = objectMapper.readTree(
                blocked.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(body.get("code").asText()).isEqualTo(ErrorCode.RATE_LIMITED.name());
        assertThat(body.get("message").asText()).isEqualTo(ErrorCode.RATE_LIMITED.message());
        assertThat(body.get("data").isNull()).isTrue();
        assertThat(body.get("serverTime").isNull())
                .as("serverTime 由注入的 Clock 填充，不该为空")
                .isFalse();
    }

    @Test
    @DisplayName("被限流时给出 Retry-After，别让客户端盲目重试")
    void sendsRetryAfterHeader() throws Exception {
        String ip = "203.0.113.11";

        createSession(ip, "retry-a");
        createSession(ip, "retry-b");
        MvcResult blocked = createSession(ip, "retry-c");

        String retryAfter = blocked.getResponse().getHeader(HttpHeaders.RETRY_AFTER);
        assertThat(retryAfter).as("缺少 Retry-After 头").isNotNull();
        assertThat(Long.parseLong(retryAfter))
                .as("返回 0 会让客户端立刻重试，等于没限流")
                .isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("限流按来源分桶：一个 IP 被打满，不影响别的 IP")
    void limitsArePerClient() throws Exception {
        String noisy = "203.0.113.12";
        String quiet = "203.0.113.13";

        createSession(noisy, "noisy-a");
        createSession(noisy, "noisy-b");
        assertThat(createSession(noisy, "noisy-c").getResponse().getStatus()).isEqualTo(429);

        assertThat(createSession(quiet, "quiet-a").getResponse().getStatus())
                .as("另一个来源不该被连累")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("健康检查不限流 —— 被限掉的监控等于没有监控")
    void healthCheckIsNotLimited() throws Exception {
        String ip = "203.0.113.14";

        // 阈值只有 2，但 /actuator 不在 /api/v1 之下，压根不参与限流
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/actuator/health").with(from(ip)))
                    .andExpect(status().isOk());
        }
    }
}
