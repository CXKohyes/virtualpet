package com.virtualpet.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.Arrays;

/**
 * WebSocket 预埋（TECH_DESIGN 5.8）。
 *
 * <p><b>养成主链路不走它。</b>状态结算、操作和进化全部走 REST，它只负责
 * 连通性验证（{@code /app/ping}）和 P1 的战报就绪通知。</p>
 *
 * <ul>
 *   <li>端点：{@code /ws}</li>
 *   <li>客户端发送前缀：{@code /app}</li>
 *   <li>订阅前缀：{@code /topic}</li>
 *   <li>P1 战报主题预留：{@code /topic/battles/{battleId}}</li>
 * </ul>
 *
 * <p>单实例使用内存 broker；将来多实例部署再引入 relay。</p>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * 允许握手的来源白名单（PRD 6.2）。
     *
     * <p>曾经写死成只放行 {@code localhost} —— 那在公网部署时会让**所有**浏览器的
     * 握手被 403 拒掉，而且失败发生在 WebSocket 层，页面上只表现为"实时通知
     * 一直未连接"，看不出是白名单的问题。所以改成配置项：开发用
     * {@code application.yml} 的默认值，生产由 {@code application-prod.yml}
     * 通过环境变量注入真实域名。</p>
     *
     * <p>注意 Spring 只在请求**带了 Origin 头**时才校验（所以后端集成测试里
     * 用 Java 客户端连接不会被拦，浏览器一定会带）。</p>
     */
    private final String[] allowedOriginPatterns;

    public WebSocketConfig(
            @Value("${app.websocket.allowed-origins}") String allowedOrigins) {
        // 手工切分而不是直接绑 List：这里多一个空格或空元素就会让来源匹配失败，
        // 而失败又是静默的（握手被拒，日志里只有一行 403），不值得冒这个险。
        this.allowedOriginPatterns = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(pattern -> !pattern.isEmpty())
                .toArray(String[]::new);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOriginPatterns);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.enableSimpleBroker("/topic");
    }
}
