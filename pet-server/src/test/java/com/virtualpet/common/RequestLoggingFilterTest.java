package com.virtualpet.common;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 请求日志过滤器的单元测试（PRD 6.6）。
 */
class RequestLoggingFilterTest {

    private RequestLoggingFilter filter;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        filter = new RequestLoggingFilter();
        logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        MDC.clear();
    }

    @Test
    @DisplayName("透传客户端 requestId，记录路径、耗时和业务结果码")
    void preservesIncomingRequestIdAndLogsBusinessCode() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/pets/me");
        request.addHeader(RequestLoggingFilter.REQUEST_ID_HEADER, "trace-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcInsideChain = new AtomicReference<>();

        FilterChain chain = (req, res) -> {
            mdcInsideChain.set(MDC.get(RequestLoggingFilter.REQUEST_ID_MDC_KEY));
            ((HttpServletRequest) req).setAttribute(RequestLoggingFilter.CODE_ATTRIBUTE, "OK");
            ((HttpServletResponse) res).setStatus(200);
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(RequestLoggingFilter.REQUEST_ID_HEADER)).isEqualTo("trace-123");
        assertThat(mdcInsideChain.get()).isEqualTo("trace-123");
        assertThat(MDC.get(RequestLoggingFilter.REQUEST_ID_MDC_KEY)).isNull();
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage())
                    .contains("GET /api/v1/pets/me -> 200 OK")
                    .contains("ms)");
            assertThat(event.getMDCPropertyMap())
                    .containsEntry(RequestLoggingFilter.REQUEST_ID_MDC_KEY, "trace-123");
        });
    }

    @Test
    @DisplayName("没有业务结果码时回退为 HTTP 状态码")
    void fallsBackToHttpStatusWhenBusinessCodeIsMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/pets");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (req, res) -> ((HttpServletResponse) res).setStatus(500);

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(RequestLoggingFilter.REQUEST_ID_HEADER)).isNotBlank();
        assertThat(appender.list).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage())
                        .contains("POST /api/v1/pets -> 500 HTTP_500"));
    }

    @Test
    @DisplayName("非法客户端 requestId 会被清理，避免日志注入和超长 Header")
    void sanitizesInvalidIncomingRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/game/config");
        request.addHeader(RequestLoggingFilter.REQUEST_ID_HEADER, "bad id with spaces");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (req, res) -> ((HttpServletResponse) res).setStatus(200);

        filter.doFilter(request, response, chain);

        String responseRequestId = response.getHeader(RequestLoggingFilter.REQUEST_ID_HEADER);
        assertThat(responseRequestId)
                .isNotBlank()
                .isNotEqualTo("bad id with spaces")
                .matches("[A-Za-z0-9._-]{1,64}");
        assertThat(appender.list).singleElement().satisfies(event ->
                assertThat(event.getMDCPropertyMap())
                        .containsEntry(RequestLoggingFilter.REQUEST_ID_MDC_KEY, responseRequestId));
    }
}
