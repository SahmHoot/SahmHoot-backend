package com.sahmhoot.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;

class StompAuthChannelInterceptorTest {

  private final StompAuthChannelInterceptor interceptor = new StompAuthChannelInterceptor();
  private final MessageChannel channel = mock(MessageChannel.class);

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/topic/rooms/1/quiz",
        "/topic/rooms/1/chat",
        "/topic/rooms/1/reaction",
        "/topic/rooms/42/presence",
        "/user/queue/rooms/1/stats",
        "/user/queue/rooms/1/me",
        "/user/queue/errors"
      })
  void 허용된_구독_경로는_통과한다(String destination) {
    Message<?> message = frame(StompCommand.SUBSCRIBE, destination);

    assertThat(interceptor.preSend(message, channel)).isSameAs(message);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/app/rooms/1/answer", "/app/rooms/1/chat", "/app/rooms/99/reaction"})
  void 허용된_전송_경로는_통과한다(String destination) {
    Message<?> message = frame(StompCommand.SEND, destination);

    assertThat(interceptor.preSend(message, channel)).isSameAs(message);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/topic/rooms/1/quiz",
        "/topic/rooms/1/chat",
        "/queue/rooms/1/me",
        "/user/queue/errors",
        "/user/queue/rooms/1/stats"
      })
  void 브로커_경로로_직접_보내는_SEND는_거부한다(String destination) {
    assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SEND, destination), channel))
        .isInstanceOf(AccessDeniedException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/topic/rooms/1/secret",
        "/topic/rooms/abc/quiz",
        "/topic/rooms/1/quiz/extra",
        "/topic/everything",
        "/queue/rooms/1/me",
        "/user/queue/rooms/1/other",
        "/app/rooms/1/chat"
      })
  void 목록_밖의_구독_경로는_거부한다(String destination) {
    assertThatThrownBy(
            () -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, destination), channel))
        .isInstanceOf(AccessDeniedException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/app/rooms/1/other", "/app/rooms/abc/chat", "/app/health"})
  void 목록_밖의_전송_경로는_거부한다(String destination) {
    assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SEND, destination), channel))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void 목적지가_없는_구독은_거부한다() {
    assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, null), channel))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void CONNECT는_지금은_통과한다() {
    Message<?> message = frame(StompCommand.CONNECT, null);

    assertThat(interceptor.preSend(message, channel)).isSameAs(message);
  }

  private static Message<byte[]> frame(StompCommand command, String destination) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
    if (destination != null) {
      accessor.setDestination(destination);
    }
    accessor.setLeaveMutable(true);
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }
}
