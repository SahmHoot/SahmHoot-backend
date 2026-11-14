package com.sahmhoot.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.user.DefaultUserDestinationResolver;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.simp.user.UserDestinationMessageHandler;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.MessageBuilder;

/** RECEIPT를 보내는 조건만 확인한다. 실제 등록 순서는 SubscribeReceiptStompIntegrationTest에서 확인한다. */
class SubscribeReceiptInterceptorTest {

  private final List<Message<?>> sent = new CopyOnWriteArrayList<>();
  private final MessageChannel outbound =
      (message, timeout) -> {
        sent.add(message);
        return true;
      };
  private final SubscribeReceiptInterceptor interceptor = new SubscribeReceiptInterceptor(outbound);
  private final ExecutorSubscribableChannel inbound = new ExecutorSubscribableChannel();
  private final ExecutorSubscribableChannel broker = new ExecutorSubscribableChannel();
  private final SimpleBrokerMessageHandler simpleBroker =
      new SimpleBrokerMessageHandler(
          inbound, new ExecutorSubscribableChannel(), broker, List.of("/topic", "/queue"));
  {
    simpleBroker.start();
  }

  private final UserDestinationMessageHandler userHandler =
      new UserDestinationMessageHandler(
          inbound,
          broker,
          new DefaultUserDestinationResolver(Mockito.mock(SimpUserRegistry.class)));

  @Test
  void 단순_브로커가_처리한_구독에만_RECEIPT를_한_번_보낸다() {
    handle(subscribe("/topic/rooms/1/quiz", "sub-0", "r-1"), null);

    assertThat(sent).singleElement().satisfies(message -> {
      StompHeaderAccessor receipt = StompHeaderAccessor.wrap(message);
      assertThat(receipt.getCommand()).isEqualTo(StompCommand.RECEIPT);
      assertThat(receipt.getReceiptId()).isEqualTo("r-1");
      assertThat(receipt.getSessionId()).isEqualTo("s-1");
    });
  }

  @Test
  void user_구독은_인바운드에서는_보내지_않고_브로커_채널에서_바뀐_목적지로_등록된_뒤_보낸다() {
    Message<?> original = subscribe("/user/queue/rooms/1/me", "sub-1", "r-2");
    interceptor.afterMessageHandled(original, inbound, userHandler, null);
    handle(original, null);
    assertThat(sent).isEmpty();

    Message<?> translated = subscribe("/queue/rooms/1/me-users-1", "sub-1", "r-2");
    simpleBroker.handleMessage(translated);
    interceptor.afterMessageHandled(translated, broker, simpleBroker, null);
    assertThat(sent).hasSize(1);
  }

  @Test
  void 등록되지_않는_구독에는_보내지_않는다() {
    handle(subscribe("/topic/rooms/1/quiz", null, "r-3"), null);
    handle(subscribe("/topic/rooms/1/quiz", "sub-2", null), null);
    handle(subscribe("/app/rooms/1/answer", "sub-3", "r-4"), null);
    handle(subscribe("/topic/rooms/1/quiz", "sub-4", "r-5"), new IllegalStateException());

    assertThat(sent).isEmpty();
  }

  @Test
  void 같은_세션에서_이미_쓴_구독_ID로_다른_채널을_구독하면_보내지_않는다() {
    handle(subscribe("/topic/rooms/1/quiz", "sub-5", "r-6"), null);
    handle(subscribe("/topic/rooms/1/chat", "sub-5", "r-7"), null);

    assertThat(sent)
        .extracting(message -> StompHeaderAccessor.wrap(message).getReceiptId())
        .containsExactly("r-6");
  }

  /** 단순 브로커가 구독을 처리한 뒤 인터셉터를 부른다(실제 채널의 순서). */
  private void handle(Message<?> message, Exception ex) {
    simpleBroker.handleMessage(message);
    interceptor.afterMessageHandled(message, inbound, simpleBroker, ex);
  }

  private static Message<?> subscribe(String destination, String subscriptionId, String receipt) {
    StompHeaderAccessor headers = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
    headers.setDestination(destination);
    headers.setSessionId("s-1");
    if (subscriptionId != null) {
      headers.setSubscriptionId(subscriptionId);
    }
    if (receipt != null) {
      headers.setReceipt(receipt);
    }
    return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
  }
}
