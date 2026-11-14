package com.sahmhoot.websocket;

import com.sahmhoot.websocket.presence.RoomPresenceInterceptor;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP 서버 설정(06 1절). 엔드포인트 /ws, 순수 WebSocket(SockJS 없음), 하트비트 10초, 순서 보장.
 *
 * <p>구독: /topic/rooms/{roomId}/..., /user/queue/... · 전송: /app/rooms/{roomId}/...
 *
 * <p>인바운드 인터셉터는 권한 검사 → 접속 추적(#65) 순서이고, receipt 헤더가 있는 구독에는 등록 뒤 RECEIPT를 보낸다(#64).
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

  private final StompAuthChannelInterceptor authInterceptor;
  private final RoomPresenceInterceptor presenceInterceptor;
  private final SubscribeReceiptInterceptor subscribeReceiptInterceptor;

  @Value("${app.cors.allowed-origins:}")
  private List<String> allowedOrigins;

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry.addEndpoint("/ws").setAllowedOrigins(allowedOrigins.toArray(String[]::new));
    registry.setPreserveReceiveOrder(true);
  }

  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry
        .enableSimpleBroker("/topic", "/queue")
        .setHeartbeatValue(new long[] {10_000, 10_000})
        .setTaskScheduler(stompHeartbeatScheduler());
    registry.setApplicationDestinationPrefixes("/app");
    registry.setUserDestinationPrefix("/user");
    registry.setPreservePublishOrder(true);
    // /user 구독은 브로커 채널에서 등록되므로 구독 RECEIPT 인터셉터를 여기에도 건다(#64).
    registry.configureBrokerChannel().interceptors(subscribeReceiptInterceptor);
  }

  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    // 순서 유지: 권한 검사에서 거부(null)된 프레임은 접속 추적까지 가지 않는다.
    // 구독 RECEIPT는 단순 브로커가 구독을 등록한 뒤(afterMessageHandled)에 보낸다.
    registration.interceptors(authInterceptor, presenceInterceptor, subscribeReceiptInterceptor);
  }

  @Bean
  public ThreadPoolTaskScheduler stompHeartbeatScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("stomp-heartbeat-");
    scheduler.initialize();
    return scheduler;
  }
}
