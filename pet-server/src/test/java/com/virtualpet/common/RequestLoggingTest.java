package com.virtualpet.common;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 请求级访问日志测试（PRD 6.6）。
 *
 * <p>要求是"每个请求记录 requestId、路径、耗时和结果码"，所以这里逐项验：
 * 响应头带 requestId、日志行里四个字段都在、业务错误码能从信封里取到。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl")
class RequestLoggingTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private ListAppender<ILoggingEvent> appender;
    private Logger filterLogger;

    @BeforeEach
    void attachAppender() {
        filterLogger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        appender = new ListAppender<>();
        appender.start();
        filterLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        filterLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("响应头带上 requestId，调用方可以拿去报障")
    void echoesRequestIdInResponseHeader() throws Exception {
        mockMvc.perform(get("/api/v1/game/config"))
                .andExpect(status().isOk())
                .andExpect(header().exists(RequestLoggingFilter.REQUEST_ID_HEADER));
    }

    @Test
    @DisplayName("调用方传了 X-Request-Id 就用它，方便跨服务串日志")
    void reusesSuppliedRequestId() throws Exception {
        mockMvc.perform(get("/api/v1/game/config").header(RequestLoggingFilter.REQUEST_ID_HEADER, "trace-abc-123"))
                .andExpect(header().string(RequestLoggingFilter.REQUEST_ID_HEADER, "trace-abc-123"));
    }

    @Test
    @DisplayName("传入的 requestId 会被清洗：去掉换行和超长部分，防止日志被注入")
    void sanitizesSuppliedRequestId() throws Exception {
        String hostile = "abc\n2026-01-01 伪造的一行日志\r\n" + "x".repeat(200);

        mockMvc.perform(get("/api/v1/game/config").header(RequestLoggingFilter.REQUEST_ID_HEADER, hostile))
                .andExpect(result -> {
                    String echoed = result.getResponse().getHeader(RequestLoggingFilter.REQUEST_ID_HEADER);
                    assertThat(echoed).doesNotContain("\n").doesNotContain("\r").doesNotContain(" ");
                    assertThat(echoed).hasSizeLessThanOrEqualTo(64);
                    assertThat(echoed).isNotBlank();
                });
    }

    @Test
    @DisplayName("每个请求都记一行：方法、路径、状态码、耗时")
    void logsMethodPathStatusAndDuration() throws Exception {
        mockMvc.perform(get("/api/v1/game/config")).andExpect(status().isOk());

        String line = lastLogLine();
        assertThat(line).contains("GET").contains("/api/v1/game/config");
        assertThat(line).contains("200");
        assertThat(line).containsPattern("\\(\\d+ms\\)");
    }

    @Test
    @DisplayName("日志里记的是业务结果码，不是笼统的 HTTP 状态")
    void logsBusinessResultCode() throws Exception {
        // 没有令牌访问受保护接口 -> 401 UNAUTHORIZED
        mockMvc.perform(get("/api/v1/pets/me")).andExpect(status().isUnauthorized());

        assertThat(lastLogLine())
                .as("应当记下错误码本身，方便按 code 聚合排查")
                .contains("UNAUTHORIZED");
    }

    @Test
    @DisplayName("成功的请求记 OK")
    void logsOkForSuccessfulRequest() throws Exception {
        mockMvc.perform(get("/api/v1/game/config")).andExpect(status().isOk());

        assertThat(lastLogLine()).contains("OK");
    }

    @Test
    @DisplayName("同一次请求里的其他日志也带着 requestId（MDC 生效）")
    void putsRequestIdInMdc() throws Exception {
        String token = newSessionToken();
        appender.list.clear();

        mockMvc.perform(post("/api/v1/pets")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .characterEncoding("UTF-8")
                        .contentType(JSON)
                        .content(objectMapper.writeValueAsString(Map.of("species", "CAT", "name", "Mimi"))))
                .andExpect(status().isOk());

        // 处理这条请求时打的业务日志，应当能从 MDC 拿到同一个 requestId
        boolean anyWithRequestId = appender.list.stream()
                .anyMatch(event -> event.getMDCPropertyMap().containsKey(RequestLoggingFilter.REQUEST_ID_MDC_KEY));
        assertThat(anyWithRequestId).isTrue();
    }

    // ---------------------------------------------------------------- 辅助

    private String lastLogLine() {
        assertThat(appender.list).as("应当有访问日志").isNotEmpty();
        return appender.list.get(appender.list.size() - 1).getFormattedMessage();
    }

    private String newSessionToken() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("deviceId", "device-" + UUID.randomUUID()));
        String response = mockMvc.perform(post("/api/v1/session")
                        .characterEncoding("UTF-8")
                        .contentType(JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(response).path("data").path("token").asText();
    }
}
