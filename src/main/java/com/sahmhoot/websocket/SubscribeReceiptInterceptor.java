package com.sahmhoot.websocket;

import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * SUBSCRIBE 프레임에 receipt 헤더가 있으면, 구독이 브로커에 등록된 뒤 RECEIPT 프레임을 보낸다.
 *
 * <p>06 1절 연결 절차는 "구독이 끝나면 state를 조회"한다. 단순 브로커는 SUBSCRIBE에 RECEIPT를 보내지 않아 클라이언트가
 * 구독 완료를 알 수 없고, 등록 전에 state를 조회하면 그 사이 발행된 메시지(ROOM_WAITING 등)를 놓친다.
 *
 * <ul>
 *   <li>구독은 단순 브로커가 등록한다. /topic·/queue 구독은 인바운드 채널에서, /user 구독은 사용자 목적지 처리기가
 *       /queue/...-user{세션}으로 바꿔 브로커 채널로 넘긴 뒤 등록된다. 그래서 두 채널에 모두 걸고, 단순 브로커가 처리한 뒤에만
 *       보낸다. 사용자 목적지 처리기가 반환된 시점은 등록 완료가 아니다(순서 보장 대기열에 들어가 나중에 등록될 수 있음)
 *   <li>인바운드 채널의 /user 구독은 단순 브로커가 무시하므로 보내지 않는다. 그래서 구독 하나에 RECEIPT는 한 번이다
 *   <li>권한 검사에서 거부된 구독, 브로커가 등록을 건너뛴 구독(구독 ID 없음·중복)은 RECEIPT를 받지 못한다
 * </ul>
 *
 * 순환 참조(설정 → 인터셉터 → 브로커 빈)를 피하려고 {@code @Lazy}로 받는다.
 */
@Component
public class SubscribeReceiptInterceptor implements ExecutorChannelInterceptor {

  private final MessageChannel clientOutboundChannel;

  public SubscribeReceiptInterceptor(
      @Lazy @Qualifier("clientOutboundChannel") MessageChannel clientOutboundChannel) {
    this.clientOutboundChannel = clientOutboundChannel;
  }

  @Override
  public void afterMessageHandled(
      Message<?> message, MessageChannel channel, MessageHandler handler, Exception ex) {
    if (ex != null) {
      return;
    }
    if (!(handler instanceof SimpleBrokerMessageHandler broker)) {
      return;
    }
    // 브로커 채널의 메시지는 사용자 목적지 처리기가 만든 것이라 STOMP 헤더 접근자가 아닐 수 있다.
    StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
    if (accessor.getCommand() != StompCommand.SUBSCRIBE
        || accessor.getReceipt() == null
        || !brokerDestination(accessor.getDestination())
        || !registered(broker, accessor)) {
      return;
    }
    StompHeaderAccessor receipt = StompHeaderAccessor.create(StompCommand.RECEIPT);
    receipt.setReceiptId(accessor.getReceipt());
    receipt.setSessionId(accessor.getSessionId());
    receipt.setLeaveMutable(true);
    clientOutboundChannel.send(
        MessageBuilder.createMessage(new byte[0], receipt.getMessageHeaders()));
  }

  /**
   * 브로커가 이 구독을 실제로 등록했는가. 구독 ID·세션 ID가 없거나 같은 세션에서 이미 쓰인 구독 ID면 브로커는 예외 없이
   * 등록을 건너뛰므로, 이 목적지로 메시지를 보낼 때 이 세션·구독 ID가 대상에 들어 있는지로 확인한다.
   *
   * <p>한계: 같은 세션에서 구독 ID를 중복으로 쓰고 그 목적지가 브로커 캐시에 이미 있으면, Spring 구독 저장소의 캐시에는
   * 들어가 이 확인을 통과한다. 구독 ID 중복은 STOMP 규격 위반이고 프론트(stompjs)는 항상 고유한 ID를 쓰며, 결과는 그
   * 클라이언트가 자기 메시지를 놓치는 것에 그친다.
   */
  private static boolean registered(
      SimpleBrokerMessageHandler broker, StompHeaderAccessor subscribe) {
    String sessionId = subscribe.getSessionId();
    String subscriptionId = subscribe.getSubscriptionId();
    if (sessionId == null || subscriptionId == null) {
      return false;
    }
    SimpMessageHeaderAccessor probe = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
    probe.setDestination(subscribe.getDestination());
    List<String> subscriptions =
        broker
            .getSubscriptionRegistry()
            .findSubscriptions(MessageBuilder.createMessage(new byte[0], probe.getMessageHeaders()))
            .get(sessionId);
    return subscriptions != null && subscriptions.contains(subscriptionId);
  }

  /** 단순 브로커가 등록하는 목적지(WebSocketConfig의 enableSimpleBroker 접두사). /user/... 원본은 여기서 제외된다. */
  private static boolean brokerDestination(String destination) {
    return destination != null
        && (destination.startsWith("/topic/") || destination.startsWith("/queue/"));
  }
}
