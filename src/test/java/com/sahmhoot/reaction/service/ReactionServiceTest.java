package com.sahmhoot.reaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sahmhoot.quiz.lock.RoomLockManager;
import com.sahmhoot.reaction.dto.SendReactionRequest;
import com.sahmhoot.reaction.dto.message.ReactionMessage;
import com.sahmhoot.reaction.exception.ReactionErrorCode;
import com.sahmhoot.reaction.exception.ReactionException;
import com.sahmhoot.reaction.service.port.ReactionRoomPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class ReactionServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-18T03:00:00Z");
  private final RoomLockManager locks = new RoomLockManager();
  private final ReactionRoomPort rooms = mock(ReactionRoomPort.class);
  private final ReactionStats stats = mock(ReactionStats.class);
  private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
  private final ReactionService service =
      new ReactionService(locks, rooms, stats, messaging, Clock.fixed(NOW, ZoneOffset.UTC));

  @BeforeEach
  void setup() {
    when(rooms.findRoom(42L)).thenReturn(Optional.of(new ReactionRoomPort.Room(1L, false)));
    when(rooms.isActiveParticipant(42L, 2L)).thenReturn(true);
    when(rooms.isActiveParticipant(42L, 1L)).thenReturn(true);
  }

  @ParameterizedTest
  @ValueSource(strings = {"👍", "❓", "😀", "👏"})
  void 허용된_이모지는_방_전체에_발행하고_집계에_더한다(String emoji) {
    doAnswer(call -> {
      assertThat(locks.isLocked(42L)).isTrue();
      return null;
    }).when(messaging).convertAndSend(anyString(), any(Object.class));

    service.send(42L, 2L, new SendReactionRequest(emoji));

    verify(messaging).convertAndSend("/topic/rooms/42/reaction", ReactionMessage.of(emoji, NOW));
    verify(stats).record(42L, 1L, emoji, NOW);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"😄", "👍👍", "👍️", " 👍", "like", "\uD83D"})
  void 허용_목록과_정확히_같지_않으면_방을_조회하지_않고_거부한다(String emoji) {
    assertError(() -> service.send(42L, 2L, new SendReactionRequest(emoji)), ReactionErrorCode.VALIDATION_FAILED);
    verifyNoInteractions(rooms, stats, messaging);
  }

  @Test
  void 본문이_없으면_거부한다() {
    assertError(() -> service.send(42L, 2L, null), ReactionErrorCode.VALIDATION_FAILED);
    verifyNoInteractions(rooms, stats, messaging);
  }

  @Test
  void 방_소유자는_보낼_수_없다() {
    assertError(() -> service.send(42L, 1L, new SendReactionRequest("👍")), ReactionErrorCode.FORBIDDEN);
    verifyNoInteractions(stats, messaging);
  }

  @Test
  void 종료된_방에는_보낼_수_없다() {
    when(rooms.findRoom(42L)).thenReturn(Optional.of(new ReactionRoomPort.Room(1L, true)));
    assertError(() -> service.send(42L, 2L, new SendReactionRequest("👍")), ReactionErrorCode.ROOM_CLOSED);
    verifyNoInteractions(stats, messaging);
  }

  @Test
  void 없는_방은_ROOM_NOT_FOUND() {
    when(rooms.findRoom(42L)).thenReturn(Optional.empty());
    assertError(() -> service.send(42L, 2L, new SendReactionRequest("👍")), ReactionErrorCode.ROOM_NOT_FOUND);
    verifyNoInteractions(stats, messaging);
  }

  @Test
  void 퇴장했거나_참여하지_않은_사용자는_보낼_수_없다() {
    assertError(() -> service.send(42L, 3L, new SendReactionRequest("👍")), ReactionErrorCode.NOT_PARTICIPANT);
    verifyNoInteractions(stats, messaging);
  }

  @Test
  void 발행에_실패한_반응은_집계에_넣지_않는다() {
    doThrow(new IllegalStateException("broker down"))
        .when(messaging).convertAndSend(anyString(), any(Object.class));

    assertThatThrownBy(() -> service.send(42L, 2L, new SendReactionRequest("👍")))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(stats);
    assertThat(locks.isLocked(42L)).isFalse();
  }

  private static void assertError(Runnable call, ReactionErrorCode code) {
    assertThatThrownBy(call::run).isInstanceOfSatisfying(ReactionException.class,
        e -> assertThat(e.code()).isEqualTo(code));
  }
}
