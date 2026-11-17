package com.sahmhoot.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sahmhoot.chat.dto.SendChatRequest;
import com.sahmhoot.chat.entity.ChatMessage;
import com.sahmhoot.chat.entity.ChatRestrictionId;
import com.sahmhoot.chat.event.ChatMessageEvent;
import com.sahmhoot.chat.exception.ChatErrorCode;
import com.sahmhoot.chat.exception.ChatException;
import com.sahmhoot.chat.repository.ChatMessageRepository;
import com.sahmhoot.chat.repository.ChatRestrictionRepository;
import com.sahmhoot.chat.service.ChatService;
import com.sahmhoot.chat.service.port.ChatRoomPort;
import com.sahmhoot.quiz.lock.RoomLockManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ChatServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-17T00:00:00.123456Z");
  private final RoomLockManager locks = new RoomLockManager();
  private final ChatRoomPort rooms = mock(ChatRoomPort.class);
  private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
  private final ChatRestrictionRepository restrictions = mock(ChatRestrictionRepository.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final ChatService service = new ChatService(locks, rooms, messages, restrictions, events,
      Clock.fixed(NOW, ZoneOffset.UTC), transactions);

  @BeforeEach void setup() {
    when(transactions.getTransaction(any())).thenAnswer(call -> {
      assertThat(locks.isLocked(42L)).isTrue();
      return new SimpleTransactionStatus();
    });
    when(rooms.findRoom(42L)).thenReturn(Optional.of(new ChatRoomPort.Room(1L, false)));
    when(rooms.findActiveParticipant(42L, 2L))
        .thenReturn(Optional.of(new ChatRoomPort.Participant(200L, 2L, "익명닉네임")));
    when(messages.save(any())).thenAnswer(call -> {
      ChatMessage message = call.getArgument(0);
      ReflectionTestUtils.setField(message, "id", 500L);
      return message;
    });
  }

  @Test void senderAndTimestampComeFromServerAndEventIsRegisteredInsideRoomLock() {
    service.send(42L, 2L, new SendChatRequest("질문😀", null));
    verify(events).publishEvent(ChatMessageEvent.of(42L,
        new com.sahmhoot.chat.dto.ChatMessageResponse(500L, 200L, "익명닉네임", false,
            "질문😀", null, null, NOW)));
    verify(transactions).commit(any());
    assertThat(locks.isLocked(42L)).isFalse();
  }

  @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "\n\t", "　"})
  void blankContentIsRejected(String content) {
    assertError(new SendChatRequest(content, null), ChatErrorCode.VALIDATION_FAILED);
  }

  @Test void unicodeCodePointsAllow300EmojiButReject301Characters() {
    service.send(42L, 2L, new SendChatRequest("😀".repeat(300), null));
    verify(messages).save(any());
    clearInvocations(messages, events);
    assertError(new SendChatRequest("😀".repeat(301), null), ChatErrorCode.VALIDATION_FAILED);
    assertError(new SendChatRequest("가".repeat(301), null), ChatErrorCode.VALIDATION_FAILED);
  }

  @Test void unpairedSurrogatesAreRejectedBeforeSavingOrRegisteringEvents() {
    char high = Character.MIN_HIGH_SURROGATE;
    char low = Character.MIN_LOW_SURROGATE;
    for (String malformed : new String[] {String.valueOf(high), String.valueOf(low),
        new String(new char[] {high, 'x', low}), "😀" + low}) {
      assertError(new SendChatRequest(malformed, null), ChatErrorCode.VALIDATION_FAILED);
    }
  }

  @Test void validSupplementaryCharactersAreNotMistakenForUnpairedSurrogates() {
    // 이 문자는 하위 16비트가 서로게이트 범위여도 정상 코드 포인트다.
    String valid = new String(Character.toChars(0x1D800));
    service.send(42L, 2L, new SendChatRequest(valid.repeat(300), null));
    verify(messages).save(argThat(message -> message.getContent().equals(valid.repeat(300))));
  }

  @Test void closedAndMissingRoomsRejectBeforeParticipantOrRestrictionReads() {
    when(rooms.findRoom(42L)).thenReturn(Optional.empty());
    assertError(new SendChatRequest("질문", null), ChatErrorCode.ROOM_NOT_FOUND);
    when(rooms.findRoom(42L)).thenReturn(Optional.of(new ChatRoomPort.Room(1L, true)));
    assertError(new SendChatRequest("질문", null), ChatErrorCode.ROOM_CLOSED);
    verify(rooms, never()).findActiveParticipant(anyLong(), anyLong());
    verifyNoInteractions(restrictions);
  }

  @Test void inactiveAndBlockedParticipantsCannotSave() {
    when(rooms.findActiveParticipant(42L, 2L)).thenReturn(Optional.empty());
    assertError(new SendChatRequest("질문", null), ChatErrorCode.NOT_PARTICIPANT);
    when(rooms.findActiveParticipant(42L, 2L))
        .thenReturn(Optional.of(new ChatRoomPort.Participant(200L, 2L, "닉네임")));
    when(restrictions.existsById(new ChatRestrictionId(42L, 200L))).thenReturn(true);
    assertError(new SendChatRequest("질문", null), ChatErrorCode.CHAT_BLOCKED);
  }

  @Test void nonOwnerCannotSupplyParentEvenWithProfessorAccount() {
    assertError(new SendChatRequest("답글", 10L), ChatErrorCode.FORBIDDEN);
    verify(messages, never()).findByIdAndRoomIdAndDeletedAtIsNull(anyLong(), anyLong());
  }

  @Test void ownerReplyUsesSameRoomUndeletedParentAndOwnerParticipantNickname() {
    when(rooms.findActiveParticipant(42L, 1L))
        .thenReturn(Optional.of(new ChatRoomPort.Participant(100L, 1L, "교수표시이름")));
    when(messages.findByIdAndRoomIdAndDeletedAtIsNull(10L, 42L))
        .thenReturn(Optional.of(new ChatMessage(42L, 200L, "원문", null, NOW)));
    service.send(42L, 1L, new SendChatRequest("답글", 10L));
    var captor = org.mockito.ArgumentCaptor.forClass(ChatMessageEvent.class);
    verify(events).publishEvent(captor.capture());
    assertThat(captor.getValue().payload()).extracting("isOwner", "parentPreview", "senderParticipantId", "nickname")
        .containsExactly(true, "원문", 100L, "교수표시이름");
  }

  @Test void ownerCannotReplyToMissingDeletedOrForeignParent() {
    when(rooms.findRoom(42L)).thenReturn(Optional.of(new ChatRoomPort.Room(2L, false)));
    assertError(new SendChatRequest("답글", 10L), ChatErrorCode.VALIDATION_FAILED);
  }

  @Test void ambientTransactionIsRejectedEvenWhenRoomLockIsAlreadyHeld() {
    locks.executeWithLock(42L, () -> {
      TransactionSynchronizationManager.setActualTransactionActive(true);
      try {
        assertThatIllegalStateException().isThrownBy(() -> service.send(42L, 2L, new SendChatRequest("질문", null)));
      } finally {
        TransactionSynchronizationManager.clear();
      }
    });
    verifyNoInteractions(transactions, rooms, messages, events);
  }

  private void assertError(SendChatRequest request, ChatErrorCode code) {
    assertThatThrownBy(() -> service.send(42L, 2L, request)).isInstanceOfSatisfying(ChatException.class,
        e -> assertThat(e.code()).isEqualTo(code));
    verify(messages, never()).save(any());
    verifyNoInteractions(events);
    verify(transactions, atLeastOnce()).rollback(any());
    assertThat(locks.isLocked(42L)).isFalse();
  }
}
