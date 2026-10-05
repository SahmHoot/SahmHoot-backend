package com.sahmhoot.quiz.service;

import com.sahmhoot.quiz.exception.BusinessException;
import com.sahmhoot.quiz.exception.ErrorCode;
import com.sahmhoot.quiz.dto.QuizResultResponse;
import com.sahmhoot.quiz.dto.StartQuizRequest;
import com.sahmhoot.quiz.dto.StartQuizResponse;
import com.sahmhoot.quiz.entity.QuizRun;
import com.sahmhoot.quiz.entity.QuizRunStatus;
import com.sahmhoot.quiz.entity.RunChoice;
import com.sahmhoot.quiz.entity.RunQuestion;
import com.sahmhoot.quiz.entity.RunQuestionStatus;
import com.sahmhoot.quiz.repository.QuizRunRepository;
import com.sahmhoot.quiz.repository.RunChoiceRepository;
import com.sahmhoot.quiz.repository.RunQuestionRepository;
import com.sahmhoot.quiz.service.port.QuestionSetPort;
import com.sahmhoot.quiz.service.port.QuestionSetSnapshot;
import com.sahmhoot.quiz.service.port.RoomPort;
import com.sahmhoot.quiz.service.port.RoomSnapshot;
import com.sahmhoot.quiz.service.port.RoomStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 1. Quiz Core 도메인의 트랜잭션 전담 서비스.
 * Spring AOP 프록시를 통해 @Transactional 경계를 정상 보장하며,
 * RoomLockManager 잠금 안에서 호출되어 "잠금 -> 트랜잭션 커밋 -> 잠금 해제" 구조를 달성한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuizCoreTxService {

  private final QuizRunRepository quizRunRepository;
  private final RunQuestionRepository runQuestionRepository;
  private final RunChoiceRepository runChoiceRepository;
  private final QuizResultService quizResultService;
  private final RoomPort roomPort;
  private final QuestionSetPort questionSetPort;

  /**
   * 퀴즈 시작 트랜잭션.
   */
  @Transactional
  public StartQuizResponse startQuiz(Long roomId, StartQuizRequest request, Long hostUserId) {
    // 1. 방 조회 및 소유자 권한/상태 검증
    RoomSnapshot room = roomPort.getRoom(roomId);
    if (!room.hostId().equals(hostUserId)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    if (room.status() == RoomStatus.CLOSED) {
      throw new BusinessException(ErrorCode.ROOM_CLOSED);
    }
    if (room.status() != RoomStatus.OPEN || quizRunRepository.existsByRoomIdAndStatus(roomId, QuizRunStatus.RUNNING)) {
      throw new BusinessException(ErrorCode.QUIZ_ALREADY_RUNNING);
    }

    // 2. 문제 세트 조회 및 소유자 본인 세트 여부 검증 (API 명세 23번 253행)
    QuestionSetSnapshot questionSet = questionSetPort.getQuestionSet(request.questionSetId());
    if (!questionSet.hostId().equals(hostUserId)) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "자신이 작성한 문제 세트만 선택할 수 있습니다.");
    }

    // 3. 회차 생성 (RUNNING)
    Instant now = Instant.now();
    QuizRun quizRun = QuizRun.builder()
        .roomId(roomId)
        .questionSetId(request.questionSetId())
        .status(QuizRunStatus.RUNNING)
        .startedAt(now)
        .build();
    quizRun = quizRunRepository.saveAndFlush(quizRun);

    // 4. 방 상태를 PLAYING으로 전환
    roomPort.updateStatus(roomId, RoomStatus.PLAYING);

    // 5. 문항 스냅샷 복사 및 1번 문항 OPEN 처리 (API 명세 23번 253행)
    questionSetPort.copyQuestionsToQuizRun(request.questionSetId(), quizRun.getId());
    List<RunQuestion> questions = runQuestionRepository.findByQuizRunIdOrderByOrderNoAsc(quizRun.getId());
    if (questions.isEmpty()) {
      throw new BusinessException(ErrorCode.EMPTY_QUESTION_SET);
    }

    RunQuestion firstQuestion = questions.getFirst();
    Long firstRunQuestionId = firstQuestion.getId();
    Instant openedAt = Instant.now();
    Instant closesAt = openedAt.plus(Duration.ofSeconds(firstQuestion.getTimeLimitSeconds()));
    firstQuestion.open(openedAt, closesAt);
    runQuestionRepository.save(firstQuestion);

    log.info("Started quiz runId={} for roomId={}, totalQuestions={}", quizRun.getId(), roomId, questions.size());
    return new StartQuizResponse(quizRun.getId(), questions.size(), firstRunQuestionId, closesAt);
  }

  /**
   * 퀴즈 중단 트랜잭션.
   */
  @Transactional
  public void abortQuiz(Long roomId, Long runId, Long hostUserId) {
    RoomSnapshot room = roomPort.getRoom(roomId);
    if (!room.hostId().equals(hostUserId)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    QuizRun quizRun = quizRunRepository.findByIdAndRoomId(runId, roomId)
        .orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_RUN_NOT_FOUND));

    if (!quizRun.isRunning()) {
      throw new BusinessException(ErrorCode.QUIZ_NOT_RUNNING);
    }

    Instant now = Instant.now();
    quizRun.abort(now);
    quizRunRepository.save(quizRun);

    // 진행 중이던 열린 문항도 마감 처리하여 더 이상 답변을 받지 않음
    runQuestionRepository.findFirstByQuizRunIdAndStatus(runId, RunQuestionStatus.OPEN)
        .ifPresent(q -> {
          q.close(now);
          runQuestionRepository.save(q);
        });

    // 방 상태를 OPEN(대기)으로 복귀
    roomPort.updateStatus(roomId, RoomStatus.OPEN);

    log.info("Aborted quiz runId={} for roomId={}", runId, roomId);
  }

  /**
   * 결과 닫기 / 대기로 복귀 트랜잭션.
   */
  @Transactional
  public void closeResult(Long roomId, Long hostUserId) {
    RoomSnapshot room = roomPort.getRoom(roomId);
    if (!room.hostId().equals(hostUserId)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    QuizRun latestRun = quizRunRepository.findTopByRoomIdOrderByIdDesc(roomId)
        .orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_SHOWING_RESULT));

    // 방이 PLAYING이고 최신 회차가 FINISHED인 상태여야 함
    if (room.status() != RoomStatus.PLAYING || !latestRun.isFinished()) {
      throw new BusinessException(ErrorCode.ROOM_NOT_SHOWING_RESULT);
    }

    // 방 상태를 OPEN(대기)으로 복귀
    roomPort.updateStatus(roomId, RoomStatus.OPEN);
    log.info("Closed quiz result for roomId={}, runId={}, returned to OPEN", roomId, latestRun.getId());
  }

  /**
   * 문항 마감 트랜잭션.
   * 실시간 규격: 마지막 문항이어도 즉시 FINISHED로 가지 않고 QUESTION_CLOSED(isLast=true) 처리 후 3초 피드백 기간을 둔다.
   */
  @Transactional
  public CloseQuestionResult closeQuestion(Long roomId, Long runQuestionId) {
    RunQuestion question = runQuestionRepository.findById(runQuestionId)
        .orElseThrow(() -> new BusinessException(ErrorCode.QUESTION_NOT_FOUND));

    QuizRun quizRun = question.getQuizRun();
    if (!quizRun.getRoomId().equals(roomId)) {
      throw new BusinessException(ErrorCode.QUESTION_NOT_FOUND);
    }

    // 중단되었거나 이미 종료된 회차의 지연 작업 무시
    if (!quizRun.isRunning() || !question.isOpen()) {
      return CloseQuestionResult.ignored();
    }

    Instant now = Instant.now();
    Instant maxAllowed = question.getClosesAt() != null ? question.getClosesAt().plusSeconds(1) : now;
    Instant actualClosedAt = now.isBefore(maxAllowed) ? now : maxAllowed;
    question.close(actualClosedAt);
    runQuestionRepository.save(question);

    log.info("Closed runQuestionId={} at {}", runQuestionId, actualClosedAt);

    List<RunQuestion> allQuestions = runQuestionRepository.findByQuizRunIdOrderByOrderNoAsc(quizRun.getId());
    int currentIndex = -1;
    for (int i = 0; i < allQuestions.size(); i++) {
      if (allQuestions.get(i).getId().equals(question.getId())) {
        currentIndex = i;
        break;
      }
    }
    if (currentIndex < 0) {
      throw new IllegalStateException("Closed question is missing from its quiz run: " + question.getId());
    }
    boolean isLast = (currentIndex == allQuestions.size() - 1);
    Integer nextOrderNo = !isLast
        ? allQuestions.get(currentIndex + 1).getOrderNo()
        : null;

    Long correctChoiceId = runChoiceRepository.findByRunQuestionIdOrderByOrderNoAsc(question.getId()).stream()
        .filter(RunChoice::isCorrect)
        .map(RunChoice::getId)
        .findFirst()
        .orElse(null);

    Instant nextOpensAt = actualClosedAt.plusSeconds(3);
    return CloseQuestionResult.closed(
        quizRun.getId(),
        question.getId(),
        correctChoiceId,
        isLast,
        nextOrderNo,
        nextOpensAt,
        actualClosedAt
    );
  }

  /**
   * 퀴즈 종료 및 결과 집계 트랜잭션.
   * 마지막 문항 마감 3초 뒤 스케줄러에 의해 호출된다.
   */
  @Transactional
  public QuizResultResponse finishQuiz(Long roomId, Long runId) {
    QuizRun quizRun = quizRunRepository.findByIdAndRoomId(runId, roomId)
        .orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_RUN_NOT_FOUND));

    if (!quizRun.isRunning()) {
      return null;
    }

    Instant now = Instant.now();
    quizRun.finish(now);
    quizRunRepository.save(quizRun);

    // rooms.status는 결과 화면 동안 PLAYING 유지 (실시간 규격 120행)
    QuizResultResponse result = quizResultService.aggregateResult(quizRun.getId());
    log.info("Finished quiz runId={} for roomId={}, result aggregated", runId, roomId);
    return result;
  }

  /**
   * 다음 문항 열기 트랜잭션.
   */
  @Transactional
  public void openQuestion(Long roomId, Long runId, int orderNo) {
    QuizRun quizRun = quizRunRepository.findByIdAndRoomId(runId, roomId)
        .orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_RUN_NOT_FOUND));

    if (!quizRun.isRunning()) {
      return;
    }

    RunQuestion question = runQuestionRepository.findByQuizRunIdAndOrderNo(runId, orderNo)
        .orElseThrow(() -> new BusinessException(ErrorCode.QUESTION_NOT_FOUND));

    if (!question.isReady()) {
      return;
    }

    Instant now = Instant.now();
    Instant closesAt = now.plus(Duration.ofSeconds(question.getTimeLimitSeconds()));
    question.open(now, closesAt);
    runQuestionRepository.save(question);
    log.info("Opened runQuestionId={} (orderNo={}) until {}", question.getId(), orderNo, closesAt);
  }
}
