package com.sahmhoot.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sahmhoot.chat.dto.ChatAuthorResponse;
import com.sahmhoot.chat.entity.ChatMessage;
import com.sahmhoot.chat.entity.ChatRestrictionId;
import com.sahmhoot.chat.event.ChatMessageDeletedEvent;
import com.sahmhoot.chat.event.ChatStatusChangedEvent;
import com.sahmhoot.chat.exception.ChatErrorCode;
import com.sahmhoot.chat.exception.ChatException;
import com.sahmhoot.chat.repository.ChatMessageRepository;
import com.sahmhoot.chat.repository.ChatPinRepository;
import com.sahmhoot.chat.repository.ChatRestrictionRepository;
import com.sahmhoot.chat.service.ChatModerationService;
import com.sahmhoot.chat.service.port.ChatAuthorEmailPort;
import com.sahmhoot.chat.service.port.ChatRoomPort;
import com.sahmhoot.quiz.lock.RoomLockManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class ChatModerationServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-18T00:00:00.123456Z");
  private static final long ROOM = 42L;
  private static final long OWNER = 1L;
  private static final long STUDENT = 2L;
  private static final long PARTICIPANT = 200L;
  private static final long MESSAGE = 500L;
  private final RoomLockManager locks = new RoomLockManager();
  private final ChatRoomPort rooms = mock(ChatRoomPort.class);
  private final ChatAuthorEmailPort authors = mock(ChatAuthorEmailPort.class);
  private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
  private final ChatPinRepository pins = mock(ChatPinRepository.class);
  private final ChatRestrictionRepository restrictions = mock(ChatRestrictionRepository.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final ChatModerationService moderation = new ChatModerationService(locks, rooms, authors,
      messages, pins, restrictions, events, Clock.fixed(NOW, ZoneOffset.UTC), transactions);

  @BeforeEach void setup() {
    when(rooms.findRoom(ROOM)).thenReturn(Optional.of(new ChatRoomPort.Room(OWNER, false)));
    when(transactions.getTransaction(any())).thenAnswer(call -> {
      assertThat(locks.isLocked(ROOM)).isTrue();
      return new SimpleTransactionStatus();
    });
  }

  @Test void allOperationsCheckOwnershipBeforeLookingUpTheTarget() {
    error(ChatErrorCode.FORBIDDEN, () -> moderation.author(ROOM, STUDENT, MESSAGE));
    error(ChatErrorCode.FORBIDDEN, () -> moderation.delete(ROOM, STUDENT, MESSAGE));
    error(ChatErrorCode.FORBIDDEN, () -> moderation.restrict(ROOM, STUDENT, PARTICIPANT));
    error(ChatErrorCode.FORBIDDEN, () -> moderation.release(ROOM, STUDENT, PARTICIPANT));
    verify(rooms, never()).findParticipants(any(), any());
    verifyNoInteractions(messages, authors, pins, restrictions, events);
  }

  @Test void refusesToRestrictTheOwnerBeforeCheckingExistingRestrictions() {
    when(rooms.findParticipants(ROOM, Set.of(100L)))
        .thenReturn(Map.of(100L, new ChatRoomPort.Participant(100L, OWNER, "교수")));
    error(ChatErrorCode.CANNOT_BLOCK_OWNER, () -> moderation.restrict(ROOM, OWNER, 100L));
    verifyNoInteractions(restrictions, events);
  }

  @Test void missingTargetsAreRejectedWithinTheRequestedRoom() {
    error(ChatErrorCode.NOT_PARTICIPANT, () -> moderation.restrict(ROOM, OWNER, PARTICIPANT));
    error(ChatErrorCode.NOT_PARTICIPANT, () -> moderation.release(ROOM, OWNER, PARTICIPANT));
    error(ChatErrorCode.VALIDATION_FAILED, () -> moderation.author(ROOM, OWNER, MESSAGE));
    error(ChatErrorCode.VALIDATION_FAILED, () -> moderation.delete(ROOM, OWNER, MESSAGE));
    verifyNoInteractions(authors, pins, restrictions, events);
  }

  @Test void closedRoomAllowsOwnerToLookUpDeletedAuthorButRejectsChanges() {
    when(rooms.findRoom(ROOM)).thenReturn(Optional.of(new ChatRoomPort.Room(OWNER, true)));
    var message = message();
    message.markDeleted(NOW);
    when(messages.findByIdAndRoomId(MESSAGE, ROOM)).thenReturn(Optional.of(message));
    stubParticipant();
    when(authors.findEmail(STUDENT)).thenReturn(Optional.of("student@example.test"));

    assertThat(moderation.author(ROOM, OWNER, MESSAGE)).isEqualTo(new ChatAuthorResponse("student@example.test"));
    error(ChatErrorCode.ROOM_CLOSED, () -> moderation.restrict(ROOM, OWNER, PARTICIPANT));
    error(ChatErrorCode.ROOM_CLOSED, () -> moderation.release(ROOM, OWNER, PARTICIPANT));
    error(ChatErrorCode.ROOM_CLOSED, () -> moderation.delete(ROOM, OWNER, MESSAGE));
    verifyNoInteractions(pins, restrictions, events);
  }

  @Test void restrictionEventsAreRegisteredOnlyForActualChanges() {
    stubParticipant();
    var id = new ChatRestrictionId(ROOM, PARTICIPANT);
    when(restrictions.existsById(id)).thenReturn(false, true, true, false);
    moderation.restrict(ROOM, OWNER, PARTICIPANT);
    moderation.restrict(ROOM, OWNER, PARTICIPANT);
    moderation.release(ROOM, OWNER, PARTICIPANT);
    moderation.release(ROOM, OWNER, PARTICIPANT);

    verify(restrictions).save(argThat(row -> row.getId().equals(id)
        && row.getRestrictedByUserId().equals(OWNER) && row.getRestrictedAt().equals(NOW)));
    verify(restrictions).deleteById(id);
    verify(events).publishEvent(ChatStatusChangedEvent.of(ROOM, STUDENT, NOW, true));
    verify(events).publishEvent(ChatStatusChangedEvent.of(ROOM, STUDENT, NOW, false));
    verifyNoMoreInteractions(events);
  }

  @Test void softDeleteKeepsWriterAndParentAndRemovesOnlyTheTargetPinOnce() {
    var message = message();
    when(messages.findByIdAndRoomId(MESSAGE, ROOM)).thenReturn(Optional.of(message));
    moderation.delete(ROOM, OWNER, MESSAGE);
    moderation.delete(ROOM, OWNER, MESSAGE);

    assertThat(message.getDeletedAt()).isEqualTo(NOW);
    assertThat(message.getSenderParticipantId()).isEqualTo(PARTICIPANT);
    assertThat(message.getParentId()).isEqualTo(499L);
    verify(pins).removePin(ROOM, MESSAGE);
    verify(events).publishEvent(ChatMessageDeletedEvent.of(ROOM, MESSAGE, NOW));
    verifyNoMoreInteractions(events);
  }

  @Test void pinDeleteFailureRollsBackAndDoesNotRegisterAnEvent() {
    when(messages.findByIdAndRoomId(MESSAGE, ROOM)).thenReturn(Optional.of(message()));
    doThrow(new DataIntegrityViolationException("pin delete failed")).when(pins).removePin(ROOM, MESSAGE);

    assertThatThrownBy(() -> moderation.delete(ROOM, OWNER, MESSAGE))
        .isInstanceOf(DataIntegrityViolationException.class);
    verify(transactions).rollback(any());
    verifyNoInteractions(events);
    assertThat(locks.isLocked(ROOM)).isFalse();
  }

  private void stubParticipant() {
    when(rooms.findParticipants(ROOM, Set.of(PARTICIPANT)))
        .thenReturn(Map.of(PARTICIPANT, new ChatRoomPort.Participant(PARTICIPANT, STUDENT, "익명")));
  }

  private ChatMessage message() {
    return new ChatMessage(ROOM, PARTICIPANT, "질문", 499L, NOW.minusSeconds(1));
  }

  private void error(ChatErrorCode code, Runnable request) {
    assertThatThrownBy(request::run).isInstanceOfSatisfying(ChatException.class,
        error -> assertThat(error.code()).isEqualTo(code));
  }
}
