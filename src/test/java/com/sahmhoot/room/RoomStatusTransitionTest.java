package com.sahmhoot.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahmhoot.room.entity.Room;
import com.sahmhoot.room.entity.RoomStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RoomStatusTransitionTest {
  @Test void openRoomStartsPlayingAndReturnsToWaiting() {
    Room room = new Room(1L, "자료구조", "123456");
    room.startPlaying();
    assertThat(room.getStatus()).isEqualTo(RoomStatus.PLAYING);
    room.returnToWaiting();
    assertThat(room.getStatus()).isEqualTo(RoomStatus.OPEN);
  }

  @Test void playingRoomCannotStartAgain() {
    Room room = new Room(1L, "자료구조", "123456");
    room.startPlaying();
    assertThatThrownBy(room::startPlaying).isInstanceOf(IllegalStateException.class);
    assertThat(room.getStatus()).isEqualTo(RoomStatus.PLAYING);
  }

  @Test void waitingRoomCannotReturnToWaiting() {
    Room room = new Room(1L, "자료구조", "123456");
    assertThatThrownBy(room::returnToWaiting).isInstanceOf(IllegalStateException.class);
    assertThat(room.getStatus()).isEqualTo(RoomStatus.OPEN);
  }

  @Test void closedRoomCannotChangeQuizStatus() {
    Room room = new Room(1L, "자료구조", "123456");
    room.close(Instant.now());
    assertThatThrownBy(room::startPlaying).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(room::returnToWaiting).isInstanceOf(IllegalStateException.class);
    assertThat(room.getStatus()).isEqualTo(RoomStatus.CLOSED);
  }
}
