package com.sahmhoot.room.service.port;

import com.sahmhoot.room.dto.RoomStateResponse.Progress;
import com.sahmhoot.room.dto.RoomStateResponse.Quiz;
import com.sahmhoot.room.dto.RoomStateResponse.QuizRun;

/**
 * #26 퀴즈 담당이 구현할 조회 계약. room은 퀴즈 엔티티·리포지토리·예외를 직접 사용하지 않는다.
 * 호출자는 방 잠금을 잡고 읽기 트랜잭션을 시작한 뒤 방 상태와 참여 권한을 검사한다.
 * 구현은 같은 트랜잭션에서 최신 회차를 읽고, myAnswers에는 전달받은 참여자의 답만 넣는다.
 * QUESTION/FEEDBACK은 RUNNING, RESULT는 FINISHED이며 대기 중에는 quiz가 null이다.
 * nextOpensAt은 스케줄러가 사용하는 실제 예약 시각을 제공한다(closedAt + 3초로 추정하지 않는다).
 * 아직 운영 구현이 없으므로 PLAYING 조회는 잘못된 대기 응답 대신 오류로 끝난다.
 */
@FunctionalInterface
public interface RoomQuizStatePort {
  Snapshot read(Long roomId, Long participantId);

  record Snapshot(QuizRun quizRun, Quiz quiz, Progress progress) {
    public static Snapshot waiting() { return new Snapshot(null, null, null); }
  }
}
