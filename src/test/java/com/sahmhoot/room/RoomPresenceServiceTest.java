package com.sahmhoot.room;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sahmhoot.room.entity.Room;
import com.sahmhoot.room.entity.RoomParticipant;
import com.sahmhoot.room.exception.RoomErrorCode;
import com.sahmhoot.room.exception.RoomException;
import com.sahmhoot.room.repository.RoomParticipantRepository;
import com.sahmhoot.room.repository.RoomRepository;
import com.sahmhoot.room.service.RoomPresenceService;
import com.sahmhoot.websocket.presence.RoomPresencePort;
import com.sahmhoot.websocket.presence.RoomPresenceSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class RoomPresenceServiceTest {
  private final RoomRepository rooms = mock(RoomRepository.class);
  private final RoomParticipantRepository participants = mock(RoomParticipantRepository.class);
  private final RoomPresencePort source = mock(RoomPresencePort.class);
  private final RoomPresenceService service = new RoomPresenceService(rooms, participants, source);
  private Room room;

  @BeforeEach void setup() {
    room = new Room(1L, "수업", "123456");
    ReflectionTestUtils.setField(room, "id", 42L);
    when(rooms.findById(42L)).thenReturn(Optional.of(room));
  }

  @Test void countsUsersOnceExcludesOwnerAndReturnsParticipantIdsRatherThanUserIds() {
    Set<Long> userIds = Set.copyOf(List.of(1L, 2L, 2L, 3L));
    when(source.getPresence(42L)).thenReturn(new RoomPresenceSnapshot(42L, userIds, true));
    when(participants.findByRoomIdAndUserIdInAndLeftAtIsNull(42L, userIds))
        .thenReturn(List.of(member(100L, 1L), member(900L, 2L)));
    assertThat(service.getOnlineParticipantIds(42L)).containsExactly(900L);
    assertThat(service.onlineCount(room)).isEqualTo(1);
    assertThatThrownBy(() -> service.getOnlineParticipantIds(42L).add(999L))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test void closingOneTabKeepsUserAndLastTabRemovalReturnsZero() {
    var connected = new RoomPresenceSnapshot(42L, Set.of(2L), false);
    when(source.getPresence(42L)).thenReturn(connected, connected,
        new RoomPresenceSnapshot(42L, Set.of(), false));
    when(participants.findByRoomIdAndUserIdInAndLeftAtIsNull(42L, Set.of(2L)))
        .thenReturn(List.of(member(900L, 2L)));
    assertThat(service.onlineCount(room)).isEqualTo(1);
    assertThat(service.onlineCount(room)).isEqualTo(1);
    assertThat(service.onlineCount(room)).isZero();
    verify(participants, times(2)).findByRoomIdAndUserIdInAndLeftAtIsNull(anyLong(), anySet());
  }

  @Test void missingAndClosedRoomsUseRoomErrorsBeforeReadingPresence() {
    when(rooms.findById(42L)).thenReturn(Optional.empty());
    assertError(RoomErrorCode.ROOM_NOT_FOUND);
    when(rooms.findById(42L)).thenReturn(Optional.of(room));
    room.close(Instant.now());
    assertError(RoomErrorCode.ROOM_CLOSED);
    verifyNoInteractions(source, participants);
  }

  @Test void invalidProviderDataIsRejectedRatherThanCountedAsZero() {
    when(source.getPresence(42L)).thenReturn(null);
    assertThatThrownBy(() -> service.getOnlineParticipantIds(42L)).isInstanceOf(NullPointerException.class);
    when(source.getPresence(42L)).thenReturn(new RoomPresenceSnapshot(99L, Set.of(), false));
    assertThatThrownBy(() -> service.onlineCount(room)).isInstanceOf(IllegalStateException.class);
  }

  @Test void missingOrClosedRoomDropsPendingPublication() {
    when(rooms.findById(42L)).thenReturn(Optional.empty());
    assertThat(service.currentCount(42L)).isEmpty();
    when(rooms.findById(42L)).thenReturn(Optional.of(room));
    room.close(Instant.now());
    assertThat(service.currentCount(42L)).isEmpty();
    verifyNoInteractions(source, participants);
  }

  @Test void currentCountReadsLatestActiveMembersExcludingOwnerInOneQuery() {
    when(source.getPresence(42L)).thenReturn(new RoomPresenceSnapshot(42L, Set.of(1L, 3L, 4L), true));
    when(participants.findByRoomIdAndUserIdInAndLeftAtIsNull(42L, Set.of(1L, 3L, 4L)))
        .thenReturn(List.of(member(100L, 1L), member(300L, 3L)));
    assertThat(service.currentCount(42L)).contains(1);
    verify(participants, times(1)).findByRoomIdAndUserIdInAndLeftAtIsNull(anyLong(), anySet());
  }

  @Test void emptyOrOwnerOnlyRoomListNeedsNoParticipantQuery() {
    assertThat(service.onlineCounts(List.of())).isEmpty();
    verifyNoInteractions(source, participants);
    when(source.getPresence(42L)).thenReturn(new RoomPresenceSnapshot(42L, Set.of(1L), true));
    assertThat(service.onlineCounts(List.of(room))).containsEntry(42L, 0);
    verifyNoInteractions(participants);
  }

  private RoomParticipant member(Long id, Long userId) {
    var member = new RoomParticipant(42L, userId, "참여자" + id);
    ReflectionTestUtils.setField(member, "id", id);
    return member;
  }

  private void assertError(RoomErrorCode code) {
    assertThatThrownBy(() -> service.getOnlineParticipantIds(42L)).isInstanceOfSatisfying(RoomException.class,
        error -> assertThat(error.getErrorCode()).isEqualTo(code));
  }
}
