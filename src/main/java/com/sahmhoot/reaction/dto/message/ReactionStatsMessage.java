package com.sahmhoot.reaction.dto.message;

import java.time.Instant;
import java.util.Map;

/**
 * 소유자 큐(/user/queue/rooms/{roomId}/stats)에 보내는 최근 60초 이모지별 집계.
 * 증가분이 아닌 전체 값이며, state의 reactionStats와 같은 형태다.
 */
public record ReactionStatsMessage(String type, Instant sentAt, Map<String, Integer> reactionStats) {
  public static ReactionStatsMessage of(Map<String, Integer> reactionStats, Instant sentAt) {
    return new ReactionStatsMessage("REACTION_STATS", sentAt, reactionStats);
  }
}
