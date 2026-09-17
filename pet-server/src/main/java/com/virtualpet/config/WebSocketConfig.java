package com.virtualpet.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket 预埋（TECH_DESIGN 5.8）。
 *
 * <p><b>MVP 只用它做连通性验证，不进入养成主链路。</b>状态结算、操作和进化全部走 REST。
 * 单实例使用内存 broker；将来多实例部署再引入 relay。</p>
 *
 * <ul>
 *   <li>端点：{@code /ws}</li>
 *   <li>客户端发送前缀：{@code /app}</li>
 *   <li>订阅前缀：{@code /topic}</li>
 *   <li>P1 战报主题预留：{@code /topic/battles/{battleId}}</li>
 * </ul>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 只放行本机开发来源，公网部署需要单独的跨域白名单（PRD 6.2）
        registry.addEndpoint("/ws").setAllowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.enableSimpleBroker("/topic");
    }
}
