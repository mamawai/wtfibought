package com.mawai.wiibservice.config;

import cn.dev33.satoken.stp.StpUtil;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.context.event.EventListener;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket配置（STOMP协议）
 */
@Slf4j
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${websocket.max-connections:10000}")
    private int maxConnections;

    @Value("${websocket.require-auth:false}")
    private boolean requireAuth;

    @Value("${websocket.heartbeat-interval:15}")
    private int heartbeatInterval;

    private static final AtomicInteger connectionCount = new AtomicInteger(0);
    private static final Set<String> connectedSessions = ConcurrentHashMap.newKeySet();

    @Bean("wsHeartbeatTaskScheduler")
    @Primary
    public TaskScheduler wsHeartbeatTaskScheduler() {
        var virtualThreadFactory = Thread.ofVirtual().name("ws-heartbeat-", 0).factory();
        var virtualScheduler = Executors.newScheduledThreadPool(1, virtualThreadFactory);
        return new ConcurrentTaskScheduler(virtualScheduler);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        long heartbeatMs = heartbeatInterval * 1000L;
        config.enableSimpleBroker("/topic", "/queue")
                .setTaskScheduler(wsHeartbeatTaskScheduler())
                .setHeartbeatValue(new long[]{heartbeatMs, heartbeatMs});
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/quotes")
                .setAllowedOriginPatterns("*")
                .addInterceptors(new HandshakeInterceptor() {
                    @Override
                    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
                        if (connectionCount.get() >= maxConnections) {
                            log.warn("WebSocket连接数已达上限: {}", maxConnections);
                            return false;
                        }

                        String token = null;
                        if (request instanceof ServletServerHttpRequest servletRequest) {
                            token = servletRequest.getServletRequest().getParameter("token");
                        }

                        if (requireAuth) {
                            if (token == null || token.isEmpty()) {
                                log.warn("WebSocket连接被拒绝：缺少认证token");
                                return false;
                            }
                            try {
                                Object loginId = StpUtil.getLoginIdByToken(token);
                                if (loginId == null) {
                                    log.warn("WebSocket连接被拒绝：无效的token");
                                    return false;
                                }
                                attributes.put("userId", loginId);
                            } catch (Exception e) {
                                log.warn("WebSocket连接被拒绝：token验证失败", e);
                                return false;
                            }
                        }

                        if (token != null && !token.isEmpty()) {
                            attributes.put("token", token);
                        }
                        return true;
                    }

                    @Override
                    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                               WebSocketHandler wsHandler, Exception exception) {
                    }
                })
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

                if (accessor != null && accessor.getCommand() != null) {
                    String sessionId = accessor.getSessionId();

                    switch (accessor.getCommand()) {
                        case CONNECT:
                            if (connectedSessions.add(sessionId)) {
                                connectionCount.incrementAndGet();
                            }
                            log.info("WebSocket连接: sessionId={}, 连接数={}", sessionId, connectionCount.get());
                            String token = (String) accessor.getSessionAttributes().get("token");
                            if (token != null) {
                                accessor.setUser(() -> sessionId);
                            }
                            break;
                        case SUBSCRIBE:
                            log.info("订阅: sessionId={}, dest={}", sessionId, accessor.getDestination());
                            break;
                        case UNSUBSCRIBE:
                            log.info("取消订阅: sessionId={}", sessionId);
                            break;
                        case DISCONNECT:
                            log.info("WebSocket断开(STOMP): sessionId={}", sessionId);
                            break;
                        default:
                            break;
                    }
                }
                return message;
            }
        });
    }

    public static int getConnectionCount() {
        return connectionCount.get();
    }

    @EventListener
    public void handleSessionDisconnect(SessionDisconnectEvent event) {
        if (connectedSessions.remove(event.getSessionId())) {
            connectionCount.decrementAndGet();
        }
        log.info("WebSocket断开: sessionId={}, 连接数={}", event.getSessionId(), connectionCount.get());
    }
}
