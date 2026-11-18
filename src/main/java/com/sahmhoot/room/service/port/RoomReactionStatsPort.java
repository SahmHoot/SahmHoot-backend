package com.sahmhoot.room.service.port;

import java.time.Instant;
import java.util.Map;

/** 소유자 state의 reactionStats(최근 60초 이모지별 개수). 반응 모듈이 구현한다. 방 잠금 안에서 호출한다. */
public interface RoomReactionStatsPort {
  Map<String, Integer> recentCounts(Long roomId, Instant at);
}
