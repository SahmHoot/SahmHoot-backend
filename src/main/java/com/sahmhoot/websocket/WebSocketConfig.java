package com.sahmhoot.websocket;

import com.sahmhoot.config.SchedulerConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/** 06 실시간 메시지 규격 1~3절. 순수 WebSocket(SockJS 없음) + SimpleBroker. */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

  private static final long[] HEARTBEAT_MS = {10_000, 10_000};

  private final String[] allowedOrigins;
  private final TaskScheduler taskScheduler;
  private final StompAuthChannelInterceptor stompAuthChannelInterceptor;

  public WebSocketConfig(
      @Value("${app.cors.allowed-origins}") String[] allowedOrigins,
      @Qualifier(SchedulerConfig.TASK_SCHEDULER) TaskScheduler taskScheduler,
      StompAuthChannelInterceptor stompAuthChannelInterceptor) {
    this.allowedOrigins = allowedOrigins;
    this.taskScheduler = taskScheduler;
    this.stompAuthChannelInterceptor = stompAuthChannelInterceptor;
  }

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry.addEndpoint("/ws").setAllowedOrigins(allowedOrigins);
    registry.setPreserveReceiveOrder(true);
  }

  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry
        .enableSimpleBroker("/topic", "/queue")
        .setHeartbeatValue(HEARTBEAT_MS)
        .setTaskScheduler(taskScheduler);
    registry.setApplicationDestinationPrefixes("/app");
    registry.setUserDestinationPrefix("/user");
    registry.setPreservePublishOrder(true);
  }

  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(stompAuthChannelInterceptor);
  }
}
