package com.virtualpet.config;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * WebSocket 连通性检查：客户端发 {@code /app/ping}，服务端广播到 {@code /topic/ping}。
 *
 * <p>这是 MVP 唯一的 WebSocket 业务，用于验证链路可用；对战通知属于 P1。</p>
 */
@Controller
public class WebSocketPingController {

    private final Clock clock;

    public WebSocketPingController(Clock clock) {
        this.clock = clock;
    }

    @MessageMapping("/ping")
    @SendTo("/topic/ping")
    public Map<String, Object> ping(Map<String, Object> payload) {
        return Map.of(
                "type", "PONG",
                "echo", payload == null ? Map.of() : payload,
                "serverTime", Instant.now(clock).toString());
    }
}
