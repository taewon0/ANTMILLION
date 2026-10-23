package com.antmillion.kis.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import com.antmillion.kis.constant.KisApiConstant;
import com.antmillion.kis.dto.KisWebSocketConnectionScope;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

@EnableWebSocketMessageBroker
@PropertySource("classpath:kisApi.properties")
@Configuration
public class KisWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${kis.app-key}")
    public String appKey;

    @Value("${kis.app-secret}")
    public String secretKey;

    @Value("${kis.mode}")
    public String mode;

    /**
     * 실전(real)/모의(virtual) mode에 따라 적절한 Base Url 반환
     */
    public String getBaseUrl() {
        if ("real".equalsIgnoreCase(mode)) {
            return KisApiConstant.REAL_BASE_URL;
        }
        return KisApiConstant.VIRTUAL_BASE_URL;
    }

    public KisWebSocketConnectionScope connectionScope() {
        return new KisWebSocketConnectionScope("real".equalsIgnoreCase(mode), appKey, secretKey);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // 서버 -> 클라이언트로 나가는 데이터의 접두사 (구독 주소)
        config.enableSimpleBroker("/topic");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // JSP에서 new SockJS('/ws-stomp')로 연결할 지점 설정
        registry.addEndpoint("/ws-stomp")
                .setAllowedOriginPatterns("*") // CORS 허용 (테스트용)
                .withSockJS(); // SockJS 지원
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
        registry
                .setMessageSizeLimit(5 * 1024 * 1024)
                .setSendBufferSizeLimit(5 * 1024 * 1024);
    }
}
