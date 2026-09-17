package com.virtualpet.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WebSocket 连通性测试（TECH_DESIGN 5.8、10.1）。
 *
 * <p>MVP 只预埋 {@code /ws} 端点和 {@code /app/ping -> /topic/ping} 通道，
 * 不实现房间和战斗广播。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WebSocketConfigTest {

    private static final long TIMEOUT_SECONDS = 10;

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("能连上 /ws，并且 /app/ping 会广播到 /topic/ping")
    void pingRoundTrip() throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());

        StompSession session = client
                .connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {
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
}
