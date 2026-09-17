package com.virtualpet.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 战报就绪通知的端到端测试（PRD 2.11）。
 *
 * <p>真的起一个服务器、真的连一条 WebSocket、真的等推送。{@link
 * com.virtualpet.battle.BattleNotifierTest} 验的是"推到哪个主题、推了什么"，
 * 但这个模块的价值恰恰在于<b>整条链路通不通</b>：订阅、broker、序列化、
 * 推送时机，任何一环断了，单测都看不出来。</p>
 *
 * <p>订阅用的是通配主题 {@code /topic/battles/*}：对战 ID 是自增的，
 * 开打之前拿不到，只能先订阅再发起。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl")
class BattleWebSocketTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("发起挑战后，订阅方能收到战报就绪通知")
    void subscriberReceivesBattleFinishedNotification() throws Exception {
        String challengerToken = newSessionWithPet("DRAGON", "小蓝");
        Session defender = newSessionWithPetAndCode("CAT", "咪咪");

        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());

        StompSession session = client
                .connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {
                })
                .get(10, TimeUnit.SECONDS);

        BlockingQueue<Map<String, Object>> received = new LinkedBlockingQueue<>();
        session.subscribe("/topic/battles/*", new StompFrameHandler() {
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

        // 订阅注册是异步的，等它真的生效再开打，否则会漏掉这条通知
        Thread.sleep(500);

        long battleId = challenge(challengerToken, defender.friendCode());

        Map<String, Object> notification = received.poll(10, TimeUnit.SECONDS);

        assertThat(notification)
                .as("十秒内应当收到战报就绪通知")
                .isNotNull();
        assertThat(notification).containsEntry("type", "BATTLE_FINISHED");
        assertThat(((Number) notification.get("battleId")).longValue()).isEqualTo(battleId);
        assertThat(notification).containsEntry("status", "FINISHED");

        // 通配订阅是广播：报文里不能有战报本体，也不能有参战双方的名字
        assertThat(notification).doesNotContainKeys(
                "timeline", "report", "challengerName", "defenderName", "winner");

        session.disconnect();
        client.stop();
    }

    // ---------------------------------------------------------------- 辅助

    private record Session(String token, String friendCode) {
    }

    private String newSessionWithPet(String species, String name) throws Exception {
        String token = newToken();
        createPet(token, species, name);
        return token;
    }

    private Session newSessionWithPetAndCode(String species, String name) throws Exception {
        String token = newSessionWithPet(species, name);
        JsonNode data = get("/api/v1/players/me/friend-code", token).path("data");
        return new Session(token, data.path("friendCode").asText());
    }

    private String newToken() throws Exception {
        ResponseEntity<String> response = rest.postForEntity("/api/v1/session",
                jsonEntity(Map.of("deviceId", "ws-" + UUID.randomUUID()), null), String.class);
        return objectMapper.readTree(response.getBody()).path("data").path("token").asText();
    }

    private void createPet(String token, String species, String name) {
        rest.exchange("/api/v1/pets", HttpMethod.POST,
                jsonEntity(Map.of("species", species, "name", name), token), String.class);
    }

    private long challenge(String token, String friendCode) throws Exception {
        ResponseEntity<String> response = rest.exchange("/api/v1/battles", HttpMethod.POST,
                jsonEntity(Map.of("friendCode", friendCode), token), String.class);
        return objectMapper.readTree(response.getBody()).path("data").path("id").asLong();
    }

    private JsonNode get(String path, String token) throws Exception {
        ResponseEntity<String> response = rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token)), String.class);
        return objectMapper.readTree(response.getBody());
    }

    private HttpEntity<String> jsonEntity(Object body, String token) {
        return new HttpEntity<>(toJson(body), bearerHeaders(token));
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception cause) {
            throw new IllegalStateException(cause);
        }
    }
}
