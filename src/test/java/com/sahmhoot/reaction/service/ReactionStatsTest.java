package com.sahmhoot.reaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sahmhoot.quiz.lock.RoomLockManager;
import com.sahmhoot.quiz.schedule.MutableClock;
import com.sahmhoot.reaction.dto.message.ReactionStatsMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class ReactionStatsTest {
  private static final Instant T0 = Instant.parse("2026-11-18T03:00:00Z");
  private static final Long ROOM = 42L;
  private static final Long OWNER = 1L;
  private static final String STATS = "/queue/rooms/42/stats";

  private final MutableClock clock = new MutableClock(T0);
  private final RoomLockManager locks = new RoomLockManager();
  private final ManualReactionScheduler scheduler = new ManualReactionScheduler(clock);
  private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
  private final ReactionStats stats = new ReactionStats(locks, scheduler, messaging, clock);
  private final List<ReactionStatsMessage> sent = new ArrayList<>();

  @BeforeEach
  void captureSent() {
    doAnswer(call -> {
      // 집계 전송은 방 잠금 안에서 이뤄져야 종료·state 조회와 섞이지 않는다.
      assertThat(locks.isLocked(ROOM)).isTrue();
      sent.add(call.getArgument(2));
      return null;
    }).when(messaging).convertAndSendToUser(anyString(), anyString(), any(Object.class));
  }

  @Test
  void 반응을_더하면_소유자_큐로_이모지별_전체_개수를_보낸다() {
    record("👍");
    record("😀");
    record("👍");

    scheduler.runNext();

    verify(messaging).convertAndSendToUser("1", STATS, sent.getFirst());
    assertThat(sent).singleElement().satisfies(message -> {
      assertThat(message.type()).isEqualTo("REACTION_STATS");
      assertThat(message.sentAt()).isEqualTo(T0);
      assertThat(message.reactionStats()).containsExactly(
          Map.entry("👍", 2), Map.entry("❓", 0), Map.entry("😀", 1), Map.entry("👏", 0));
    });
  }

  @Test
  void 연속된_반응은_0_5초_간격으로_묶어_최신_값만_보낸다() {
    record("👍");
    scheduler.runNext();
    clock.setTo(T0.plusMillis(100));
    record("❓");
    clock.setTo(T0.plusMillis(200));
    record("❓");

    assertThat(scheduler.activeTimes()).containsExactly(T0.plusMillis(500));
    scheduler.runNext();

    assertThat(sent).hasSize(2);
    assertThat(sent.get(1).sentAt()).isEqualTo(T0.plusMillis(500));
    assertThat(sent.get(1).reactionStats()).containsEntry("👍", 1).containsEntry("❓", 2);
  }

  @Test
  void 마지막_0_집계_직후에_온_반응도_0_5초_간격을_지킨다() {
    record("👍");
    scheduler.runNext();
    scheduler.runNext();
    assertThat(sent.getLast().reactionStats()).containsEntry("👍", 0);

    clock.setTo(T0.plusSeconds(60).plusMillis(100));
    record("❓");

    assertThat(scheduler.activeTimes()).containsExactly(T0.plusSeconds(60).plusMillis(500));
    scheduler.runNext();
    assertThat(sent).hasSize(3);
    assertThat(sent.getLast().sentAt()).isEqualTo(T0.plusSeconds(60).plusMillis(500));
    assertThat(sent.getLast().reactionStats()).containsEntry("❓", 1);
  }

  @Test
  void 집계는_60초_미만인_반응만_센다() {
    record("👏");

    assertThat(counts(T0.plusMillis(59_999))).containsEntry("👏", 1);
    assertThat(counts(T0.plusSeconds(60))).containsEntry("👏", 0);
  }

  @Test
  void 반응이_60초를_지나면_줄어든_값을_보내고_비면_방_집계를_지운다() {
    record("👍");
    clock.setTo(T0.plusSeconds(10));
    record("👍");
    scheduler.runNext();

    assertThat(scheduler.activeTimes()).containsExactly(T0.plusSeconds(60));
    scheduler.runNext();
    assertThat(sent.getLast().reactionStats()).containsEntry("👍", 1);

    assertThat(scheduler.activeTimes()).containsExactly(T0.plusSeconds(70));
    scheduler.runNext();
    assertThat(sent.getLast().reactionStats()).containsEntry("👍", 0);
    assertThat(sent.getLast().sentAt()).isEqualTo(T0.plusSeconds(70));
    // 마지막 전송 뒤 0.5초 동안은 간격을 지키려고 창을 남겨 둔다.
    assertThat(scheduler.activeTimes()).containsExactly(T0.plusSeconds(70).plusMillis(500));
    scheduler.runNext();
    assertThat(sent).hasSize(3);
    assertThat(scheduler.active()).isEmpty();
    assertThat(counts(T0.plusSeconds(71))).containsOnly(
        Map.entry("👍", 0), Map.entry("❓", 0), Map.entry("😀", 0), Map.entry("👏", 0));
  }

  @Test
  void state_조회가_만료_항목을_먼저_지워도_예약은_줄어든_값을_보낸다() {
    record("👍");
    scheduler.runNext();

    clock.setTo(T0.plusSeconds(61));
    assertThat(counts(T0.plusSeconds(61))).containsEntry("👍", 0);
    scheduler.runNext();

    assertThat(sent).hasSize(2);
    assertThat(sent.getLast().reactionStats()).containsEntry("👍", 0);
    scheduler.runNext();
    assertThat(sent).hasSize(2);
    assertThat(scheduler.active()).isEmpty();
  }

  @Test
  void 앞당겨진_예약_때문에_취소된_이전_예약이_뒤늦게_실행돼도_아무것도_하지_않는다() {
    record("👍");
    scheduler.runNext();
    var expiry = scheduler.active().getFirst();
    assertThat(expiry.at).isEqualTo(T0.plusSeconds(60));

    clock.setTo(T0.plusSeconds(10));
    record("😀");
    var current = scheduler.active().getFirst();
    assertThat(expiry.cancelled).isTrue();
    assertThat(current.at).isEqualTo(T0.plusSeconds(10));

    clock.setTo(T0.plusSeconds(10));
    scheduler.run(expiry);
    assertThat(sent).hasSize(1);
    assertThat(scheduler.active()).containsExactly(current);

    scheduler.runNext();
    assertThat(sent).hasSize(2);
    assertThat(sent.getLast().reactionStats()).containsEntry("👍", 1).containsEntry("😀", 1);
  }

  @Test
  void 마지막_0_집계_전송이_실패하면_다시_보내고_성공한_뒤에_집계를_지운다() {
    record("👍");
    scheduler.runNext();
    doThrow(new IllegalStateException("broker down")).doAnswer(call -> {
      sent.add(call.getArgument(2));
      return null;
    }).when(messaging).convertAndSendToUser(anyString(), anyString(), any(Object.class));

    scheduler.runNext();
    assertThat(sent).hasSize(1);
    assertThat(scheduler.activeTimes()).containsExactly(T0.plusSeconds(60).plusMillis(500));

    scheduler.runNext();
    assertThat(sent).hasSize(2);
    assertThat(sent.getLast().reactionStats()).containsEntry("👍", 0);
    scheduler.runNext();
    assertThat(sent).hasSize(2);
    assertThat(scheduler.active()).isEmpty();
  }

  @Test
  void 예약에_실패해도_다음_반응이나_state_조회가_다시_예약한다() {
    scheduler.failNext = true;
    record("👍");
    assertThat(scheduler.active()).isEmpty();

    assertThat(counts(T0)).containsEntry("👍", 1);
    assertThat(scheduler.activeTimes()).containsExactly(T0);
    scheduler.runNext();
    assertThat(sent).singleElement().satisfies(message ->
        assertThat(message.reactionStats()).containsEntry("👍", 1));
  }

  @Test
  void 방이_종료되면_예약을_취소하고_늦게_실행된_예약도_보내지_않는다() {
    record("👍");
    var pending = scheduler.active().getFirst();

    locks.executeWithLock(ROOM, () -> stats.cancelAll(ROOM));

    assertThat(pending.cancelled).isTrue();
    scheduler.run(pending);
    verify(messaging, never()).convertAndSendToUser(anyString(), anyString(), any(Object.class));
    assertThat(counts(T0)).containsEntry("👍", 0);
  }

  @Test
  void 방마다_따로_센다() {
    record("👍");
    locks.executeWithLock(7L, () -> stats.record(7L, 3L, "👏", clock.instant()));

    assertThat(counts(T0)).containsEntry("👍", 1).containsEntry("👏", 0);
    assertThat(locks.executeWithLock(7L, () -> stats.recentCounts(7L, T0)))
        .containsEntry("👍", 0).containsEntry("👏", 1);
  }

  private void record(String emoji) {
    locks.executeWithLock(ROOM, () -> stats.record(ROOM, OWNER, emoji, clock.instant()));
  }

  private Map<String, Integer> counts(Instant at) {
    return new LinkedHashMap<>(locks.executeWithLock(ROOM, () -> stats.recentCounts(ROOM, at)));
  }
}
