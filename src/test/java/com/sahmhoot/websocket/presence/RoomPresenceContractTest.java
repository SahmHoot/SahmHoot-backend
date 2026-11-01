package com.sahmhoot.websocket.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RoomPresenceContractTest {

  @Test
  void snapshotCannotBeChangedByPublisherOrConsumer() {
    Set<Long> userIds = new HashSet<>(Set.of(201L));
    RoomPresenceSnapshot snapshot = new RoomPresenceSnapshot(10L, userIds, false);

    userIds.add(202L);

    assertThat(snapshot.onlineUserIds()).containsExactly(201L);
    assertThatThrownBy(() -> snapshot.onlineUserIds().add(203L))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void ownerCannotBeOnlineWithoutAnyOnlineUser() {
    assertThatThrownBy(() -> new RoomPresenceSnapshot(10L, Set.of(), true))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void ownerLastSessionDisconnectIsVisibleToRoom() {
    RoomPresenceSnapshot previous = new RoomPresenceSnapshot(10L, Set.of(100L, 201L), true);
    RoomPresenceSnapshot current = new RoomPresenceSnapshot(10L, Set.of(201L), false);

    RoomPresenceChangedEvent event = new RoomPresenceChangedEvent(previous, current, Instant.now());

    assertThat(event.ownerConnectionChanged()).isTrue();
    assertThat(event.current().onlineUserIds()).containsExactly(201L);
  }

  @Test
  void participantChangeDoesNotChangeOwnerConnection() {
    RoomPresenceSnapshot previous = new RoomPresenceSnapshot(10L, Set.of(201L), false);
    RoomPresenceSnapshot current = new RoomPresenceSnapshot(10L, Set.of(201L, 202L), false);

    RoomPresenceChangedEvent event = new RoomPresenceChangedEvent(previous, current, Instant.now());

    assertThat(event.ownerConnectionChanged()).isFalse();
  }

  @Test
  void duplicateUserStateCannotProduceChangeEvent() {
    RoomPresenceSnapshot previous = new RoomPresenceSnapshot(10L, Set.of(201L), false);
    RoomPresenceSnapshot sameUsers = new RoomPresenceSnapshot(10L, Set.of(201L), false);

    assertThatThrownBy(() -> new RoomPresenceChangedEvent(previous, sameUsers, Instant.now()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void eventCannotMixRooms() {
    RoomPresenceSnapshot previous = new RoomPresenceSnapshot(10L, Set.of(), false);
    RoomPresenceSnapshot otherRoom = new RoomPresenceSnapshot(20L, Set.of(201L), false);

    assertThatThrownBy(() -> new RoomPresenceChangedEvent(previous, otherRoom, Instant.now()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
