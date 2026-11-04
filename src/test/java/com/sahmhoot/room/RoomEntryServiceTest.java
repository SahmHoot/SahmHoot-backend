package com.sahmhoot.room;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sahmhoot.common.ratelimit.RateLimiter;
import com.sahmhoot.quiz.lock.RoomLockManager;
import com.sahmhoot.room.dto.JoinRoomRequest;
import com.sahmhoot.room.entity.Room;
import com.sahmhoot.room.entity.RoomParticipant;
import com.sahmhoot.room.exception.RoomErrorCode;
import com.sahmhoot.room.exception.RoomException;
import com.sahmhoot.room.repository.RoomParticipantRepository;
import com.sahmhoot.room.repository.RoomRepository;
import com.sahmhoot.room.service.RoomEntryService;
import com.sahmhoot.room.service.RoomUserLookup;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class RoomEntryServiceTest {
  private final RoomRepository rooms = mock(RoomRepository.class);
  private final RoomParticipantRepository participants = mock(RoomParticipantRepository.class);
  private final RoomUserLookup users = mock(RoomUserLookup.class);
  private final RateLimiter limiter = new RateLimiter();
  private final RoomLockManager locks = new RoomLockManager();
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final RoomEntryService service = new RoomEntryService(
      rooms, participants, users, limiter, locks, transactions);
  private Room room;

  @BeforeEach void setup() {
    room = new Room(1L, "수업", "123456");
    ReflectionTestUtils.setField(room, "id", 42L);
    when(users.findById(anyLong())).thenAnswer(call -> Optional.of(
        new RoomUserLookup.RoomUser(call.getArgument(0), "STUDENT", "이름")));
    when(rooms.findByCode("123456")).thenReturn(Optional.of(room));
    when(rooms.findById(42L)).thenReturn(Optional.of(room));
    when(transactions.getTransaction(any())).thenAnswer(call -> {
      assertThat(locks.isLocked(42L)).isTrue();
      return new SimpleTransactionStatus();
    });
    when(participants.saveAndFlush(any())).thenAnswer(call -> {
      RoomParticipant participant = call.getArgument(0);
      ReflectionTestUtils.setField(participant, "id", 100L);
      return participant;
    });
  }

  @Test void lookupAndJoinShareLimitAcrossRoomsAndFailures() {
    for (int i = 0; i < 10; i++) {
      service.byCode(2L, "123456");
      assertError(() -> service.join(2L, "999999", new JoinRoomRequest("학생")),
          RoomErrorCode.ROOM_NOT_FOUND);
    }
    assertError(() -> service.byCode(2L, "123456"), RoomErrorCode.RATE_LIMITED);
    assertError(() -> service.join(2L, "123456", new JoinRoomRequest("학생")),
        RoomErrorCode.RATE_LIMITED);
    assertThat(service.byCode(3L, "123456").roomId()).isEqualTo(42L);
    verifyNoInteractions(transactions);
  }

  @Test void deletedUserIsRejectedBeforeRoomLookupOrJoining() {
    when(users.findById(2L)).thenReturn(Optional.empty());
    assertError(() -> service.byCode(2L, "123456"), RoomErrorCode.UNAUTHORIZED);
    assertError(() -> service.join(2L, "123456", new JoinRoomRequest("학생")),
        RoomErrorCode.UNAUTHORIZED);
    verifyNoInteractions(rooms, participants, transactions);
  }

  @Test void firstJoinUsesAuthenticatedUserAndNormalizedNickname() {
    var result = service.join(2L, "123456", new JoinRoomRequest("  학생  "));
    assertThat(result.created()).isTrue();
    assertThat(result.response().participantId()).isEqualTo(100L);
    verify(participants).saveAndFlush(argThat(participant ->
        participant.getRoomId().equals(42L) && participant.getUserId().equals(2L)
            && participant.getNickname().equals("학생")));
    verify(transactions).commit(any());
  }

  @Test void rejoinReusesParticipantAndReactivatesWithoutRenaming() {
    RoomParticipant participant = new RoomParticipant(42L, 2L, "기존 닉네임");
    ReflectionTestUtils.setField(participant, "id", 100L);
    ReflectionTestUtils.setField(participant, "leftAt", Instant.now());
    when(participants.findByRoomIdAndUserId(42L, 2L)).thenReturn(Optional.of(participant));
    var result = service.join(2L, "123456", new JoinRoomRequest("다른 닉네임"));
    assertThat(result.created()).isFalse();
    assertThat(result.response().participantId()).isEqualTo(100L);
    assertThat(result.response().nickname()).isEqualTo("기존 닉네임");
    assertThat(participant.getLeftAt()).isNull();
    verify(participants, never()).saveAndFlush(any());
    verify(participants, never()).existsByRoomIdAndNickname(anyLong(), anyString());
  }

  @Test void closedBetweenCodeLookupAndTransactionCannotBeJoined() {
    Room closed = new Room(1L, "수업", "123456");
    closed.close(Instant.now());
    when(rooms.findById(42L)).thenReturn(Optional.of(closed));
    assertError(() -> service.join(2L, "123456", new JoinRoomRequest("학생")),
        RoomErrorCode.ROOM_NOT_FOUND);
    verifyNoInteractions(participants);
    verify(transactions).rollback(any());
  }

  @Test void forbiddenAndDuplicateNicknamesDoNotCreateParticipants() {
    assertError(() -> service.join(2L, "123456", new JoinRoomRequest("교수님")),
        RoomErrorCode.NICKNAME_NOT_ALLOWED);
    when(participants.existsByRoomIdAndNickname(42L, "학생")).thenReturn(true);
    assertError(() -> service.join(2L, "123456", new JoinRoomRequest("학생")),
        RoomErrorCode.NICKNAME_ALREADY_USED);
    verify(participants, never()).saveAndFlush(any());
  }

  private void assertError(Runnable action, RoomErrorCode code) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(RoomException.class,
        error -> assertThat(error.getErrorCode()).isEqualTo(code));
  }
}
