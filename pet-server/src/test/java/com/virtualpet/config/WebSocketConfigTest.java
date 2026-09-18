package com.virtualpet.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WebSocket 连通性与来源白名单测试（TECH_DESIGN 5.8、10.1）。
 *
 * <p>这里把 {@code app.websocket.allowed-origins} 覆盖成一个非默认值，
 * 好让白名单的两端都验到：配置里的来源能握手，配置外的来源被拒。
 * 如果这个配置项没被真正读取，下面两条会同时失败 —— 这正是要防的回归
 * （曾经写死成只放行 localhost，公网部署后所有浏览器都连不上）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "app.websocket.allowed-origins=https://pet.example.com")
class WebSocketConfigTest {

    private static final long TIMEOUT_SECONDS = 10;
    private static final String ALLOWED_ORIGIN = "https://pet.example.com";

    @LocalServerPort
    private int port;

    private String url() {
        return "ws://localhost:" + port + "/ws";
    }

    private WebSocketStompClient newClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());
        return client;
    }

    @Test
    @DisplayName("能连上 /ws，并且 /app/ping 会广播到 /topic/ping")
    void pingRoundTrip() throws Exception {
        StompSession session = newClient()
                .connectAsync(url(), new StompSessionHandlerAdapter() {
                })
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        try {
            BlockingQueue<Map<String, Object>> received = new LinkedBlockingQueue<>();
            session.subscribe("/topic/ping", new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return Map.class;
                }

                @Override
                @SuppressWarnings("unchecked")
                public void handleFrame(StompHeaders headers, Object payload) {
                    received.add((Map<String, Object>) payload);
                }
            });

            session.send("/app/ping", Map.of("hello", "world"));

            Map<String, Object> pong = received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(pong).as("没有在 %s 秒内收到 /topic/ping 的广播", TIMEOUT_SECONDS).isNotNull();
            assertThat(pong.get("type")).isEqualTo("PONG");
            assertThat(pong.get("echo")).isEqualTo(Map.of("hello", "world"));
            assertThat(pong.get("serverTime")).isNotNull();
        } finally {
            session.disconnect();
        }
    }

    @Test
    @DisplayName("白名单里的来源能建立握手")
    void allowedOriginCanConnect() throws Exception {
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.set(HttpHeaders.ORIGIN, ALLOWED_ORIGIN);

        StompSession session = newClient()
                .connectAsync(url(), handshakeHeaders, new StompHeaders(), new StompSessionHandlerAdapter() {
                })
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        try {
            assertThat(session.isConnected()).isTrue();
        } finally {
            session.disconnect();
        }
    }

    @Test
    @DisplayName("白名单外的来源握手被拒 —— 公网部署时最容易踩的就是这条")
    void foreignOriginIsRejected() {
        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.set(HttpHeaders.ORIGIN, "https://evil.example.com");

        assertThatThrownBy(() -> newClient()
                .connectAsync(url(), handshakeHeaders, new StompHeaders(), new StompSessionHandlerAdapter() {
                })
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .as("白名单外的主机来源不该握手成功")
                .isInstanceOf(ExecutionException.class);
    }
}
