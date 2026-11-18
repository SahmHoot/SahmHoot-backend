package com.sahmhoot.reaction.service.port;

import java.util.Optional;

/** 반응 전송 권한 확인에 필요한 방 정보. 구현은 방 모듈이 한다. */
public interface ReactionRoomPort {
  Optional<Room> findRoom(Long roomId);

  /** 퇴장하지 않은 참여자인지. 소유자도 참여자다. */
  boolean isActiveParticipant(Long roomId, Long userId);

  record Room(Long ownerId, boolean closed) {}
}
