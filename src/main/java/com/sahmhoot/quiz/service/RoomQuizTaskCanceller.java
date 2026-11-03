package com.sahmhoot.quiz.service;

import com.sahmhoot.quiz.schedule.QuizTaskScheduler;
import com.sahmhoot.room.service.port.RoomTaskCanceller;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 수업 종료 시 그 방의 퀴즈 예약(마감·다음 문항·결과)을 모두 취소한다(#23).
 *
 * <p>방 모듈의 {@code RoomManagementService.close}가 종료 트랜잭션 커밋 뒤, 방 잠금 안에서
 * 등록된 모든 {@link RoomTaskCanceller}를 부른다. 이 클래스는 그 호출을 퀴즈 예약 도구로 넘기기만 한다.
 * {@link RoomQuizAbortAdapter}처럼 방이 정의한 포트를 퀴즈가 구현하는 방식이다.
 *
 * <p>예약 도구가 직접 이 포트를 구현하지 않고 따로 두는 이유: 예약 도구를 방 모듈에 묶지 않고,
 * 테스트에서 예약 도구를 가짜로 바꿔도 "수업 종료 → 예약 취소" 연결이 그대로 남게 하기 위해서다.
 */
@Component
@RequiredArgsConstructor
public class RoomQuizTaskCanceller implements RoomTaskCanceller {

  private final QuizTaskScheduler taskScheduler;

  @Override
  public void cancelAll(Long roomId) {
    taskScheduler.cancelAll(roomId);
  }
}
