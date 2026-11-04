package com.sahmhoot.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.sahmhoot.room.dto.CreateRoomRequest;
import com.sahmhoot.room.entity.Room;
import com.sahmhoot.room.entity.RoomParticipant;
import com.sahmhoot.room.exception.RoomErrorCode;
import com.sahmhoot.room.exception.RoomException;
import com.sahmhoot.room.repository.RoomParticipantRepository;
import com.sahmhoot.room.repository.RoomRepository;
import com.sahmhoot.room.service.JoinCodeGenerator;
import com.sahmhoot.room.service.RoomService;
import com.sahmhoot.room.service.RoomUserLookup;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class RoomServiceTest {
  private final RoomRepository rooms = mock(RoomRepository.class);
  private final RoomParticipantRepository participants = mock(RoomParticipantRepository.class);
  private final RoomUserLookup users = mock(RoomUserLookup.class);
  private final JoinCodeGenerator codes = mock(JoinCodeGenerator.class);
  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
  private final RoomService service = new RoomService(
      rooms, participants, users, codes, transactions, "https://class.example/");

  @Test void deletedUserCannotCreateRoom() {
    when(users.findById(1L)).thenReturn(Optional.empty());
    assertError(RoomErrorCode.UNAUTHORIZED);
    verifyNoInteractions(rooms, participants, codes, transactions);
  }

  @Test void currentStudentRoleCannotCreateRoom() {
    when(users.findById(1L)).thenReturn(Optional.of(
        new RoomUserLookup.RoomUser(1L, "STUDENT", "학생")));
    assertError(RoomErrorCode.FORBIDDEN);
    verifyNoInteractions(rooms, participants, codes, transactions);
  }

  @Test void professorCreatesRoomUsingCurrentDatabaseName() {
    when(users.findById(1L)).thenReturn(Optional.of(
        new RoomUserLookup.RoomUser(1L, "PROFESSOR", "현재 이름")));
    when(codes.generate()).thenReturn("123456");
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    when(rooms.saveAndFlush(any(Room.class))).thenAnswer(invocation -> {
      Room room = invocation.getArgument(0);
      ReflectionTestUtils.setField(room, "id", 42L);
      return room;
    });

    var response = service.create(1L, new CreateRoomRequest("수업"));

    assertThat(response.id()).isEqualTo(42L);
    assertThat(response.joinUrl()).isEqualTo("https://class.example/join/123456");
    var participant = ArgumentCaptor.forClass(RoomParticipant.class);
    verify(participants).saveAndFlush(participant.capture());
    assertThat(participant.getValue().getUserId()).isEqualTo(1L);
    assertThat(participant.getValue().getNickname()).isEqualTo("현재 이름");
  }

  private void assertError(RoomErrorCode code) {
    assertThatThrownBy(() -> service.create(1L, new CreateRoomRequest("수업")))
        .isInstanceOfSatisfying(RoomException.class,
            error -> assertThat(error.getErrorCode()).isEqualTo(code));
  }
}
