package com.sahmhoot.reaction.service;

import com.sahmhoot.quiz.service.port.RoomLockPort;
import com.sahmhoot.reaction.dto.SendReactionRequest;
import com.sahmhoot.reaction.dto.message.ReactionMessage;
import com.sahmhoot.reaction.exception.ReactionErrorCode;
import com.sahmhoot.reaction.exception.ReactionException;
import com.sahmhoot.reaction.service.port.ReactionRoomPort;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * 이모지 반응 전송(RE-01·05). 저장하지 않고 방 전체에 바로 발행한 뒤 소유자용 60초 집계에 더한다.
 *
 * <p>방 종료(RoomManagementService.close)와 같은 방 잠금을 잡는다. 종료 뒤에 도착한 반응은 ROOM_CLOSED로
 * 거부되고, ROOM_ENDED 뒤에 REACTION이 발행되지 않는다.
 */
@Service
@RequiredArgsConstructor
public class ReactionService {
  private final RoomLockPort locks;
  private final ReactionRoomPort rooms;
  private final ReactionStats stats;
  private final SimpMessagingTemplate messaging;
  private final Clock clock;

  public void send(Long roomId, Long userId, SendReactionRequest request) {
    String emoji = request == null ? null : request.emoji();
    if (emoji == null || !ReactionStats.EMOJIS.contains(emoji)) {
      throw new ReactionException(ReactionErrorCode.VALIDATION_FAILED);
    }
    locks.executeWithLock(roomId, () -> {
      var room = rooms.findRoom(roomId)
          .orElseThrow(() -> new ReactionException(ReactionErrorCode.ROOM_NOT_FOUND));
      if (room.closed()) throw new ReactionException(ReactionErrorCode.ROOM_CLOSED);
      if (room.ownerId().equals(userId)) throw new ReactionException(ReactionErrorCode.FORBIDDEN);
      if (!rooms.isActiveParticipant(roomId, userId)) {
        throw new ReactionException(ReactionErrorCode.NOT_PARTICIPANT);
      }
      Instant now = clock.instant();
      // 발행에 실패한 반응은 집계에도 넣지 않는다.
      messaging.convertAndSend("/topic/rooms/" + roomId + "/reaction", ReactionMessage.of(emoji, now));
      stats.record(roomId, room.ownerId(), emoji, now);
    });
  }
}
