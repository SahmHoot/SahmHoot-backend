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
    RoomSnapshot room = getRoomOwnedBy(roomId, hostUserId);
    if (room.status() == RoomStatus.CLOSED) {
      throw new BusinessException(ErrorCode.ROOM_CLOSED);
    }
    if (room.status() != RoomStatus.OPEN || quizRunRepository.existsByRoomIdAndStatus(roomId, QuizRunStatus.RUNNING)) {
      throw new BusinessException(ErrorCode.QUIZ_ALREADY_RUNNING);
    }

    validateQuestionSetOwner(request.questionSetId(), hostUserId);
    QuizRun quizRun = createQuizRun(roomId, request.questionSetId());
    roomPort.updateStatus(roomId, RoomStatus.PLAYING);
    StartQuizResponse response = copyQuestionsAndOpenFirst(quizRun);

    log.info("Started quiz runId={} for roomId={}, totalQuestions={}", quizRun.getId(), roomId, response.totalQuestions());
    return response;
  }

  /**
   * 퀴즈 중단 트랜잭션.
   */
  @Transactional
  public void abortQuiz(Long roomId, Long runId, Long hostUserId) {
    getRoomOwnedBy(roomId, hostUserId);

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
    RoomSnapshot room = getRoomOwnedBy(roomId, hostUserId);

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
    int currentIndex = findQuestionIndex(allQuestions, question.getId());
    boolean isLast = (currentIndex == allQuestions.size() - 1);
    Integer nextOrderNo = !isLast
        ? allQuestions.get(currentIndex + 1).getOrderNo()
        : null;

    Instant nextOpensAt = actualClosedAt.plusSeconds(3);
    return CloseQuestionResult.closed(
        quizRun.getId(),
        question.getId(),
        findCorrectChoiceId(question.getId()),
        isLast,
        nextOrderNo,
        nextOpensAt,
        actualClosedAt
    );
  }

  /**
   * 퀴즈 종료 및 결과 집계 트랜잭션.
   * 후속 스케줄러 연동 시 마지막 문항 마감 3초 뒤 호출한다.
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

  private RoomSnapshot getRoomOwnedBy(Long roomId, Long hostUserId) {
    RoomSnapshot room = roomPort.getRoom(roomId);
    if (!room.hostId().equals(hostUserId)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    return room;
  }

  private void validateQuestionSetOwner(Long questionSetId, Long hostUserId) {
    QuestionSetSnapshot questionSet = questionSetPort.getQuestionSet(questionSetId);
    if (!questionSet.hostId().equals(hostUserId)) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "자신이 작성한 문제 세트만 선택할 수 있습니다.");
    }
  }

  private QuizRun createQuizRun(Long roomId, Long questionSetId) {
    QuizRun quizRun = QuizRun.builder()
        .roomId(roomId)
        .questionSetId(questionSetId)
        .status(QuizRunStatus.RUNNING)
        .startedAt(Instant.now())
        .build();
    return quizRunRepository.saveAndFlush(quizRun);
  }

  private StartQuizResponse copyQuestionsAndOpenFirst(QuizRun quizRun) {
    questionSetPort.copyQuestionsToQuizRun(quizRun.getQuestionSetId(), quizRun.getId());
    List<RunQuestion> questions = runQuestionRepository.findByQuizRunIdOrderByOrderNoAsc(quizRun.getId());
    if (questions.isEmpty()) {
      throw new BusinessException(ErrorCode.EMPTY_QUESTION_SET);
    }

    RunQuestion firstQuestion = questions.getFirst();
    Instant openedAt = Instant.now();
    Instant closesAt = openedAt.plus(Duration.ofSeconds(firstQuestion.getTimeLimitSeconds()));
    firstQuestion.open(openedAt, closesAt);
    runQuestionRepository.save(firstQuestion);
    return new StartQuizResponse(quizRun.getId(), questions.size(), firstQuestion.getId(), closesAt);
  }

  private int findQuestionIndex(List<RunQuestion> questions, Long questionId) {
    for (int i = 0; i < questions.size(); i++) {
      if (questions.get(i).getId().equals(questionId)) {
        return i;
      }
    }
    throw new IllegalStateException("Closed question is missing from its quiz run: " + questionId);
  }

  private Long findCorrectChoiceId(Long questionId) {
    return runChoiceRepository.findByRunQuestionIdOrderByOrderNoAsc(questionId).stream()
        .filter(RunChoice::isCorrect)
        .map(RunChoice::getId)
        .findFirst()
        .orElse(null);
  }
}
