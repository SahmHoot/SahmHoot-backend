package com.sahmhoot.room.service.port;

import com.sahmhoot.room.dto.RoomStateResponse.Message;
import java.util.List;

/** 방 잠금·읽기 트랜잭션·참여 권한 확인 후 호출하는 채팅 복구 계약. */
public interface RoomChatStatePort {
  Snapshot read(Long roomId, Long participantId);

  record Snapshot(boolean chatBlocked, List<Message> recentMessages, List<Message> pinnedMessages) {
    public Snapshot {
      recentMessages = List.copyOf(recentMessages);
      pinnedMessages = List.copyOf(pinnedMessages);
    }
  }
}
