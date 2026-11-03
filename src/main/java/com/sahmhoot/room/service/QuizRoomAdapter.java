package com.sahmhoot.room.service;

import com.sahmhoot.quiz.service.port.RoomPort;
import com.sahmhoot.quiz.service.port.RoomSnapshot;
import com.sahmhoot.quiz.service.port.RoomStatus;
import com.sahmhoot.room.entity.Room;
import com.sahmhoot.room.entity.RoomParticipant;
import com.sahmhoot.room.repository.RoomParticipantRepository;
import com.sahmhoot.room.repository.RoomRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 퀴즈가 정한 {@link RoomPort}의 실제 구현.
 *
 * <p>퀴즈 서비스가 {@code roomPort.findRoom(..)}을 부르면 스프링이 이 클래스의 메서드를 실행한다.
 * 방 데이터는 방 모듈의 엔티티·리포지토리로만 읽고 바꾸며, 퀴즈 패키지에서는 포트
 * 인터페이스와 데이터 상자(record)만 가져다 쓴다. 퀴즈 오류 클래스는 쓰지 않는다.
 */
@Service
@RequiredArgsConstructor
public class QuizRoomAdapter implements RoomPort {
  private final RoomRepository rooms;
  private final RoomParticipantRepository participants;

  /** 방을 찾아 퀴즈에 필요한 값(ID, 주인, 상태)만 담아 돌려준다. 방 엔티티는 넘기지 않는다. */
  @Override
  public Optional<RoomSnapshot> findRoom(Long roomId) {
    return rooms.findById(roomId)
        .map(room -> new RoomSnapshot(room.getId(), room.getHostId(), toQuizStatus(room)));
  }

  /**
   * 상태 전환 규칙은 {@link Room#startPlaying()}, {@link Room#returnToWaiting()}이 검사한다.
   * MANDATORY: 퀴즈의 트랜잭션 안에서만 실행되므로, 퀴즈 처리가 실패하면 방 상태 변경도 함께 취소된다.
   */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void updateStatus(Long roomId, RoomStatus status) {
    Room room = rooms.findById(roomId)
        .orElseThrow(() -> new IllegalStateException("상태를 바꿀 방이 없다: roomId=" + roomId));
    switch (status) {
      case PLAYING -> room.startPlaying();
      case OPEN -> room.returnToWaiting();
      case CLOSED -> throw new IllegalArgumentException("수업 종료는 방 모듈의 종료 API로만 한다.");
    }
    // 바로 DB에 반영해서, 같은 트랜잭션 안에서 SQL로 방 상태를 읽는 코드도 바뀐 값을 보게 한다.
    rooms.saveAndFlush(room);
  }

  /** 퇴장하지 않은 참여자만 찾는다(left_at IS NULL). */
  @Override
  public Optional<Long> findParticipantId(Long roomId, Long userId) {
    return participants.findByRoomIdAndUserIdAndLeftAtIsNull(roomId, userId).map(RoomParticipant::getId);
  }

  /** 방 모듈의 상태 enum을 포트의 상태 enum으로 옮긴다. 두 enum은 값 이름이 같다. */
  private static RoomStatus toQuizStatus(Room room) {
    return RoomStatus.valueOf(room.getStatus().name());
  }
}
