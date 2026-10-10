package com.sahmhoot.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sahmhoot.quiz.exception.BusinessException;
import com.sahmhoot.quiz.exception.ErrorCode;
import com.sahmhoot.quiz.dto.QuizResultResponse;
import com.sahmhoot.quiz.dto.StartQuizRequest;
import com.sahmhoot.quiz.dto.StartQuizResponse;
import com.sahmhoot.quiz.entity.QuestionType;
import com.sahmhoot.quiz.entity.QuizRun;
import com.sahmhoot.quiz.entity.QuizRunStatus;
import com.sahmhoot.quiz.entity.RunQuestion;
import com.sahmhoot.quiz.entity.RunQuestionStatus;
import com.sahmhoot.quiz.entity.RunChoice;
import com.sahmhoot.quiz.repository.QuizRunRepository;
import com.sahmhoot.quiz.repository.RunChoiceRepository;
import com.sahmhoot.quiz.repository.RunQuestionRepository;
import com.sahmhoot.quiz.service.port.QuestionSetPort;
import com.sahmhoot.quiz.service.port.QuestionSetSnapshot;
import com.sahmhoot.quiz.service.port.RoomPort;
import com.sahmhoot.quiz.service.port.RoomSnapshot;
import com.sahmhoot.quiz.service.port.RoomStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QuizCoreTxServiceTest {

  @Mock
  private QuizRunRepository quizRunRepository;

  @Mock
  private RunQuestionRepository runQuestionRepository;

  @Mock
  private RunChoiceRepository runChoiceRepository;

  @Mock
  private QuizResultService quizResultService;

  @Mock
  private RoomPort roomPort;

  @Mock
  private QuestionSetPort questionSetPort;

  private QuizCoreTxService quizCoreTxService;

  @BeforeEach
  void setUp() {
    quizCoreTxService = new QuizCoreTxService(
        quizRunRepository,
        runQuestionRepository,
        runChoiceRepository,
        quizResultService,
        roomPort,
        questionSetPort
    );
  }

  @Test
  @DisplayName("startQuiz: 방 소유자가 아니면 FORBIDDEN 예외를 던진다 (Issue 3)")
  void startQuiz_hostMismatch_throwsForbidden() {
    Long roomId = 42L;
    Long hostUserId = 1L;
    Long otherUserId = 99L;
    StartQuizRequest request = new StartQuizRequest(7L);

    given(roomPort.getRoom(roomId)).willReturn(new RoomSnapshot(roomId, hostUserId, RoomStatus.OPEN));

    assertThatThrownBy(() -> quizCoreTxService.startQuiz(roomId, request, otherUserId))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.FORBIDDEN);
  }

  @Test
  @DisplayName("startQuiz: 방이 존재하지 않으면 ROOM_NOT_FOUND 예외를 던진다 (Issue 4)")
  void startQuiz_roomNotFound_throwsRoomNotFound() {
    Long roomId = 42L;
    StartQuizRequest request = new StartQuizRequest(7L);

    given(roomPort.getRoom(roomId)).willThrow(new BusinessException(ErrorCode.ROOM_NOT_FOUND));

    assertThatThrownBy(() -> quizCoreTxService.startQuiz(roomId, request, 1L))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.ROOM_NOT_FOUND);
  }

  @Test
  @DisplayName("startQuiz: 방이 OPEN이 아니면(PLAYING) QUIZ_ALREADY_RUNNING 예외를 던진다 (Issue 5)")
  void startQuiz_roomPlaying_throwsQuizAlreadyRunning() {
    Long roomId = 42L;
    Long hostUserId = 1L;
    StartQuizRequest request = new StartQuizRequest(7L);

    given(roomPort.getRoom(roomId)).willReturn(new RoomSnapshot(roomId, hostUserId, RoomStatus.PLAYING));

    assertThatThrownBy(() -> quizCoreTxService.startQuiz(roomId, request, hostUserId))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.QUIZ_ALREADY_RUNNING);
  }

  @Test
  @DisplayName("startQuiz: 자신이 만든 세트가 아니면 FORBIDDEN 예외를 던진다 (Issue 3)")
  void startQuiz_questionSetHostMismatch_throwsForbidden() {
    Long roomId = 42L;
    Long hostUserId = 1L;
    Long otherHostId = 2L;
    Long questionSetId = 7L;
    StartQuizRequest request = new StartQuizRequest(questionSetId);

    given(roomPort.getRoom(roomId)).willReturn(new RoomSnapshot(roomId, hostUserId, RoomStatus.OPEN));
    given(quizRunRepository.existsByRoomIdAndStatus(roomId, QuizRunStatus.RUNNING)).willReturn(false);
    given(questionSetPort.getQuestionSet(questionSetId)).willReturn(new QuestionSetSnapshot(questionSetId, otherHostId));

    assertThatThrownBy(() -> quizCoreTxService.startQuiz(roomId, request, hostUserId))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.FORBIDDEN);
  }

  @Test
  @DisplayName("startQuiz: 시작 성공 시 rooms.status를 PLAYING으로 바꾸고 1번 문항을 OPEN 처리한다 (Issue 5)")
  void startQuiz_success_transitionsRoomToPlaying() {
    Long roomId = 42L;
    Long hostUserId = 1L;
    Long questionSetId = 7L;
    StartQuizRequest request = new StartQuizRequest(questionSetId);

    given(roomPort.getRoom(roomId)).willReturn(new RoomSnapshot(roomId, hostUserId, RoomStatus.OPEN));
    given(quizRunRepository.existsByRoomIdAndStatus(roomId, QuizRunStatus.RUNNING)).willReturn(false);
    given(questionSetPort.getQuestionSet(questionSetId)).willReturn(new QuestionSetSnapshot(questionSetId, hostUserId));

    QuizRun savedRun = QuizRun.builder()
        .id(77L)
        .roomId(roomId)
        .questionSetId(questionSetId)
        .status(QuizRunStatus.RUNNING)
        .startedAt(Instant.now())
        .build();
    given(quizRunRepository.saveAndFlush(any(QuizRun.class))).willReturn(savedRun);

    RunQuestion q1 = RunQuestion.builder()
        .id(810L)
        .quizRun(savedRun)
        .type(QuestionType.MULTIPLE_CHOICE)
        .content("스택의 특징은?")
        .timeLimitSeconds(15)
        .orderNo(1)
        .status(RunQuestionStatus.READY)
        .build();
    given(runQuestionRepository.findByQuizRunIdOrderByOrderNoAsc(77L)).willReturn(List.of(q1));

    StartQuizResponse response = quizCoreTxService.startQuiz(roomId, request, hostUserId);

    assertThat(response.runId()).isEqualTo(77L);
    assertThat(response.totalQuestions()).isEqualTo(1);
    assertThat(response.firstRunQuestionId()).isEqualTo(810L);
    assertThat(q1.getStatus()).isEqualTo(RunQuestionStatus.OPEN);

    // rooms.status PLAYING 전환 및 문제 복사 검증
    verify(roomPort).updateStatus(roomId, RoomStatus.PLAYING);
    verify(questionSetPort).copyQuestionsToQuizRun(questionSetId, 77L);
    verify(runQuestionRepository).save(q1);
  }

  @Test
  @DisplayName("startQuiz: 문제 세트에 문항이 없으면 EMPTY_QUESTION_SET 예외를 던진다")
  void startQuiz_emptyQuestionSet_throwsEmptyQuestionSet() {
    Long roomId = 42L;
    Long hostUserId = 1L;
    Long questionSetId = 7L;
    StartQuizRequest request = new StartQuizRequest(questionSetId);

    given(roomPort.getRoom(roomId)).willReturn(new RoomSnapshot(roomId, hostUserId, RoomStatus.OPEN));
    given(quizRunRepository.existsByRoomIdAndStatus(roomId, QuizRunStatus.RUNNING)).willReturn(false);
    given(questionSetPort.getQuestionSet(questionSetId)).willReturn(new QuestionSetSnapshot(questionSetId, hostUserId));

    QuizRun savedRun = QuizRun.builder()
        .id(77L)
        .roomId(roomId)
        .questionSetId(questionSetId)
        .status(QuizRunStatus.RUNNING)
        .startedAt(Instant.now())
        .build();
    given(quizRunRepository.saveAndFlush(any(QuizRun.class))).willReturn(savedRun);
    given(runQuestionRepository.findByQuizRunIdOrderByOrderNoAsc(77L)).willReturn(List.of());

    assertThatThrownBy(() -> quizCoreTxService.startQuiz(roomId, request, hostUserId))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.EMPTY_QUESTION_SET);
  }

  @Test
  @DisplayName("abortQuiz: 중단 성공 시 회차를 ABORTED로 변경하고 방 상태를 OPEN으로 복귀시킨다 (Issue 5)")
  void abortQuiz_success_transitionsRoomToOpen() {
    Long roomId = 42L;
    Long hostUserId = 1L;
    Long runId = 77L;

    QuizRun quizRun = QuizRun.builder()
        .id(runId)
        .roomId(roomId)
        .status(QuizRunStatus.RUNNING)
        .build();

    RunQuestion openQuestion = RunQuestion.builder()
        .id(810L)
        .quizRun(quizRun)
        .status(RunQuestionStatus.OPEN)
        .build();

    given(roomPort.getRoom(roomId)).willReturn(new RoomSnapshot(roomId, hostUserId, RoomStatus.PLAYING));
    given(quizRunRepository.findByIdAndRoomId(runId, roomId)).willReturn(Optional.of(quizRun));
    given(runQuestionRepository.findFirstByQuizRunIdAndStatus(runId, RunQuestionStatus.OPEN))
        .willReturn(Optional.of(openQuestion));

    quizCoreTxService.abortQuiz(roomId, runId, hostUserId);

    assertThat(quizRun.getStatus()).isEqualTo(QuizRunStatus.ABORTED);
    assertThat(openQuestion.getStatus()).isEqualTo(RunQuestionStatus.CLOSED);

    // rooms.status OPEN 복귀 검증
    verify(roomPort).updateStatus(roomId, RoomStatus.OPEN);
  }

  @Test
  @DisplayName("closeResult: 방이 PLAYING이고 최신 회차가 FINISHED일 때 방을 OPEN으로 복귀시킨다 (Issue 5)")
  void closeResult_success_transitionsRoomToOpen() {
    Long roomId = 42L;
    Long hostUserId = 1L;
    Long runId = 77L;

    QuizRun finishedRun = QuizRun.builder()
        .id(runId)
        .roomId(roomId)
        .status(QuizRunStatus.FINISHED)
        .build();

    given(roomPort.getRoom(roomId)).willReturn(new RoomSnapshot(roomId, hostUserId, RoomStatus.PLAYING));
    given(quizRunRepository.findTopByRoomIdOrderByIdDesc(roomId)).willReturn(Optional.of(finishedRun));

    quizCoreTxService.closeResult(roomId, hostUserId);

    verify(roomPort).updateStatus(roomId, RoomStatus.OPEN);
  }

  @Test
  @DisplayName("closeResult: 이미 OPEN(대기) 상태인 방에서 호출하면 ROOM_NOT_SHOWING_RESULT 예외를 던진다 (Issue 5)")
  void closeResult_alreadyOpen_throwsRoomNotShowingResult() {
    Long roomId = 42L;
    Long hostUserId = 1L;
    Long runId = 77L;

    QuizRun finishedRun = QuizRun.builder()
        .id(runId)
        .roomId(roomId)
        .status(QuizRunStatus.FINISHED)
        .build();

    // 방이 이미 OPEN 상태
    given(roomPort.getRoom(roomId)).willReturn(new RoomSnapshot(roomId, hostUserId, RoomStatus.OPEN));
    given(quizRunRepository.findTopByRoomIdOrderByIdDesc(roomId)).willReturn(Optional.of(finishedRun));

    assertThatThrownBy(() -> quizCoreTxService.closeResult(roomId, hostUserId))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.ROOM_NOT_SHOWING_RESULT);
  }

  @Test
  @DisplayName("closeQuestion: 마지막 문항이어도 즉시 FINISHED/결과집계하지 않고 RUNNING 유지 및 isLast=true를 반환한다 (Issue 2)")
  void closeQuestion_lastQuestion_doesNotFinishImmediately() {
    Long roomId = 42L;
    Long runId = 77L;
    Long questionId = 812L;

    QuizRun quizRun = QuizRun.builder()
        .id(runId)
        .roomId(roomId)
        .questionSetId(7L)
        .status(QuizRunStatus.RUNNING)
        .build();

    RunQuestion question = RunQuestion.builder()
        .id(questionId)
        .quizRun(quizRun)
        .type(QuestionType.MULTIPLE_CHOICE)
        .content("마지막 문제")
        .timeLimitSeconds(15)
        .orderNo(2)
        .status(RunQuestionStatus.OPEN)
        .closesAt(Instant.now().plusSeconds(10))
        .build();

    RunChoice correctChoice = RunChoice.builder()
        .id(3001L)
        .runQuestion(question)
        .orderNo(1)
        .content("정답 보기")
        .correct(true)
        .build();

    given(runQuestionRepository.findById(questionId)).willReturn(Optional.of(question));
    given(runQuestionRepository.findByQuizRunIdOrderByOrderNoAsc(runId))
        .willReturn(List.of(
            RunQuestion.builder().id(811L).orderNo(1).build(),
            question
        ));
    given(runChoiceRepository.findByRunQuestionIdOrderByOrderNoAsc(questionId))
        .willReturn(List.of(correctChoice));

    CloseQuestionResult result = quizCoreTxService.closeQuestion(roomId, questionId);

    // 문항은 CLOSED, 회차는 3초 동안 RUNNING 유지!
    assertThat(question.getStatus()).isEqualTo(RunQuestionStatus.CLOSED);
    assertThat(quizRun.getStatus()).isEqualTo(QuizRunStatus.RUNNING);
    assertThat(result.processed()).isTrue();
    assertThat(result.isLast()).isTrue();
    assertThat(result.correctChoiceId()).isEqualTo(3001L);
    assertThat(result.nextOrderNo()).isNull();
    assertThat(result.nextOpensAt()).isNotNull();

    // 결과 집계는 3초 뒤 finishQuiz에서 수행되므로 closeQuestion 중에는 호출되지 않아야 함
    verify(quizResultService, never()).aggregateResult(any());
  }

  @Test
  @DisplayName("closeQuestion: 마지막 문항이 아니면 nextOrderNo와 다음 문항 오픈 시각을 반환한다 (Issue 2)")
  void closeQuestion_notLastQuestion_returnsNextOrderNo() {
    Long roomId = 42L;
    Long runId = 77L;
    Long questionId = 811L;

    QuizRun quizRun = QuizRun.builder()
        .id(runId)
        .roomId(roomId)
        .status(QuizRunStatus.RUNNING)
        .build();

    RunQuestion q1 = RunQuestion.builder()
        .id(questionId)
        .quizRun(quizRun)
        .orderNo(1)
        .status(RunQuestionStatus.OPEN)
        .closesAt(Instant.now().plusSeconds(10))
        .build();

    RunQuestion q2 = RunQuestion.builder()
        .id(812L)
        .quizRun(quizRun)
        .orderNo(2)
        .status(RunQuestionStatus.READY)
        .build();

    RunChoice correctChoice = RunChoice.builder()
        .id(3002L)
        .runQuestion(q1)
        .orderNo(2)
        .correct(true)
        .build();

    given(runQuestionRepository.findById(questionId)).willReturn(Optional.of(q1));
    given(runQuestionRepository.findByQuizRunIdOrderByOrderNoAsc(runId)).willReturn(List.of(q1, q2));
    given(runChoiceRepository.findByRunQuestionIdOrderByOrderNoAsc(questionId)).willReturn(List.of(correctChoice));

    CloseQuestionResult result = quizCoreTxService.closeQuestion(roomId, questionId);

    assertThat(result.processed()).isTrue();
    assertThat(result.isLast()).isFalse();
    assertThat(result.correctChoiceId()).isEqualTo(3002L);
    assertThat(result.nextOrderNo()).isEqualTo(2);
    assertThat(result.nextOpensAt()).isNotNull();
  }

  @Test
  @DisplayName("finishQuiz: 3초 뒤 스케줄러가 호출하면 회차를 FINISHED로 변경하고 결과를 집계한다 (Issue 2)")
  void finishQuiz_success() {
    Long roomId = 42L;
    Long runId = 77L;

    QuizRun runningRun = QuizRun.builder()
        .id(runId)
        .roomId(roomId)
        .status(QuizRunStatus.RUNNING)
        .build();

    QuizResultResponse expectedResult = new QuizResultResponse(List.of());

    given(quizRunRepository.findByIdAndRoomId(runId, roomId)).willReturn(Optional.of(runningRun));
    given(quizResultService.aggregateResult(runId)).willReturn(expectedResult);

    QuizResultResponse result = quizCoreTxService.finishQuiz(roomId, runId);

    assertThat(runningRun.getStatus()).isEqualTo(QuizRunStatus.FINISHED);
    assertThat(result).isEqualTo(expectedResult);
    verify(quizResultService).aggregateResult(runId);
  }
}
