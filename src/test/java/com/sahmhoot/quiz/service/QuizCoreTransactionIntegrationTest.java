package com.sahmhoot.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.sahmhoot.quiz.exception.BusinessException;
import com.sahmhoot.quiz.exception.ErrorCode;
import com.sahmhoot.quiz.lock.RoomLockManager;
import com.sahmhoot.quiz.dto.AnswerProgressResponse;
import com.sahmhoot.quiz.dto.StartQuizRequest;
import com.sahmhoot.quiz.dto.StartQuizResponse;
import com.sahmhoot.quiz.dto.SubmitAnswerRequest;
import com.sahmhoot.quiz.entity.Answer;
import com.sahmhoot.quiz.entity.QuestionType;
import com.sahmhoot.quiz.entity.QuizRun;
import com.sahmhoot.quiz.entity.QuizRunStatus;
import com.sahmhoot.quiz.entity.RunChoice;
import com.sahmhoot.quiz.entity.RunQuestion;
import com.sahmhoot.quiz.entity.RunQuestionStatus;
import com.sahmhoot.quiz.repository.AnswerRepository;
import com.sahmhoot.quiz.repository.QuizRunRepository;
import com.sahmhoot.quiz.repository.RunChoiceRepository;
import com.sahmhoot.quiz.repository.RunQuestionRepository;
import com.sahmhoot.quiz.service.port.QuestionSetPort;
import com.sahmhoot.quiz.service.port.QuestionSetSnapshot;
import com.sahmhoot.quiz.service.port.RoomPort;
import com.sahmhoot.quiz.service.port.RoomSnapshot;
import com.sahmhoot.quiz.service.port.RoomStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Spring 트랜잭션 AOP 프록시 및 잠금-트랜잭션 실행 순서 검증 통합 테스트 (Issue 1).
 * 실시간 규격 5절의 "잠금 획득 -> 트랜잭션 서비스 호출(커밋) -> 잠금 해제"가
 * 실제 Spring IoC 컨테이너와 트랜잭션 인터셉터 프록시를 통해 동작함을 증명한다.
 */
@SpringJUnitConfig(QuizCoreTransactionIntegrationTest.TestConfig.class)
class QuizCoreTransactionIntegrationTest {

  @Configuration
  @EnableTransactionManagement
  static class TestConfig {

    static List<String> eventLog = new ArrayList<>();

    @Bean
    public RoomLockManager roomLockManager() {
      return new RoomLockManager();
    }

    @Bean
    public PlatformTransactionManager transactionManager(RoomLockManager roomLockManager) {
      return new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
          boolean locked = roomLockManager.isLocked(42L);
          eventLog.add("TX_BEGIN (locked=" + locked + ")");
          return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) throws TransactionException {
          boolean locked = roomLockManager.isLocked(42L);
          eventLog.add("TX_COMMIT (locked=" + locked + ")");
        }

        @Override
        public void rollback(TransactionStatus status) throws TransactionException {
          boolean locked = roomLockManager.isLocked(42L);
          eventLog.add("TX_ROLLBACK (locked=" + locked + ")");
        }
      };
    }

    @Bean
    public QuizRunRepository quizRunRepository() {
      return mock(QuizRunRepository.class);
    }

    @Bean
    public RunQuestionRepository runQuestionRepository() {
      return mock(RunQuestionRepository.class);
    }

    @Bean
    public RunChoiceRepository runChoiceRepository() {
      return mock(RunChoiceRepository.class);
    }

    @Bean
    public AnswerRepository answerRepository() {
      return mock(AnswerRepository.class);
    }

    @Bean
    public QuizResultService quizResultService() {
      return mock(QuizResultService.class);
    }

    @Bean
    public RoomPort roomPort() {
      return mock(RoomPort.class);
    }

    @Bean
    public QuestionSetPort questionSetPort() {
      return mock(QuestionSetPort.class);
    }

    @Bean
    public QuizCoreTxService quizCoreTxService(
        QuizRunRepository quizRunRepository,
        RunQuestionRepository runQuestionRepository,
        RunChoiceRepository runChoiceRepository,
        QuizResultService quizResultService,
        RoomPort roomPort,
        QuestionSetPort questionSetPort
    ) {
      return new QuizCoreTxService(
          quizRunRepository,
          runQuestionRepository,
          runChoiceRepository,
          quizResultService,
          roomPort,
          questionSetPort
      );
    }

    @Bean
    public QuizCoreService quizCoreService(
        RoomLockManager roomLockManager,
        QuizCoreTxService quizCoreTxService
    ) {
      return new QuizCoreService(roomLockManager, quizCoreTxService);
    }

    @Bean
    public AnswerTxService answerTxService(
        AnswerRepository answerRepository,
        RunQuestionRepository runQuestionRepository,
        RunChoiceRepository runChoiceRepository
    ) {
      return new AnswerTxService(answerRepository, runQuestionRepository, runChoiceRepository);
    }

    @Bean
    public AnswerService answerService(
        RoomLockManager roomLockManager,
        AnswerTxService answerTxService,
        QuizCoreService quizCoreService
    ) {
      return new AnswerService(roomLockManager, answerTxService, quizCoreService);
    }
  }

  @Autowired
  private RoomLockManager roomLockManager;

  @Autowired
  private QuizCoreService quizCoreService;

  @Autowired
  private QuizCoreTxService quizCoreTxService;

  @Autowired
  private AnswerService answerService;

  @Autowired
  private AnswerTxService answerTxService;

  @Autowired
  private RoomPort roomPort;

  @Autowired
  private QuestionSetPort questionSetPort;

  @Autowired
  private QuizRunRepository quizRunRepository;

  @Autowired
  private RunQuestionRepository runQuestionRepository;

  @Autowired
  private RunChoiceRepository runChoiceRepository;

  @Autowired
  private AnswerRepository answerRepository;

  @BeforeEach
  void clearEvents() {
    TestConfig.eventLog.clear();
  }

  @Test
  @DisplayName("QuizCoreTxService와 AnswerTxService는 Spring CGLIB AOP 트랜잭션 프록시 객체이다 (Issue 1)")
  void txServices_areSpringAopProxies() {
    assertThat(AopUtils.isAopProxy(quizCoreTxService))
        .as("QuizCoreTxService must be a Spring AOP proxy bean")
        .isTrue();

    assertThat(AopUtils.isCglibProxy(quizCoreTxService))
        .as("QuizCoreTxService must be CGLIB proxied for class-level transactions")
        .isTrue();

    assertThat(AopUtils.isAopProxy(answerTxService))
        .as("AnswerTxService must be a Spring AOP proxy bean")
        .isTrue();

    assertThat(AopUtils.isCglibProxy(answerTxService))
        .as("AnswerTxService must be CGLIB proxied for class-level transactions")
        .isTrue();
  }

  @Test
  @DisplayName("startQuiz 호출 시 방 잠금 안에서 Spring 트랜잭션이 활성화되고 커밋 후 잠금이 해제된다 (Issue 1)")
  void startQuiz_executesWithinSpringTransactionAndLock() {
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
        .content("문제 1")
        .timeLimitSeconds(15)
        .orderNo(1)
        .status(RunQuestionStatus.READY)
        .build();
    given(runQuestionRepository.findByQuizRunIdOrderByOrderNoAsc(77L)).willReturn(List.of(q1));

    StartQuizResponse response = quizCoreService.startQuiz(roomId, request, hostUserId);

    assertThat(response).isNotNull();
    assertThat(response.runId()).isEqualTo(77L);

    // 잠금 획득 상태에서 트랜잭션 시작 -> 커밋되고, 이후 잠금이 풀렸는지 검증
    assertThat(TestConfig.eventLog).containsExactly("TX_BEGIN (locked=true)", "TX_COMMIT (locked=true)");
    assertThat(roomLockManager.isLocked(roomId)).isFalse();
  }

  @Test
  @DisplayName("submitAnswer 호출 시 방 잠금 안에서 Spring 트랜잭션이 활성화되고 커밋 후 잠금이 해제된다 (Issue 1)")
  void submitAnswer_executesWithinSpringTransactionAndLock() {
    Long roomId = 42L;
    Long participantId = 900L;
    Long runQuestionId = 812L;
    Long choiceId = 3001L;

    QuizRun quizRun = QuizRun.builder()
        .id(77L)
        .roomId(roomId)
        .status(QuizRunStatus.RUNNING)
        .build();

    RunQuestion question = RunQuestion.builder()
        .id(runQuestionId)
        .quizRun(quizRun)
        .status(RunQuestionStatus.OPEN)
        .closesAt(Instant.now().plusSeconds(10))
        .build();

    RunChoice choice = RunChoice.builder()
        .id(choiceId)
        .runQuestion(question)
        .build();

    given(runQuestionRepository.findById(runQuestionId)).willReturn(Optional.of(question));
    given(runChoiceRepository.findByIdAndRunQuestionId(choiceId, runQuestionId)).willReturn(Optional.of(choice));
    given(answerRepository.findByRunQuestionIdAndParticipantId(runQuestionId, participantId))
        .willReturn(Optional.empty());
    given(answerRepository.countByRunQuestionId(runQuestionId)).willReturn(1L);

    AnswerProgressResponse response = answerService.submitAnswer(
        roomId,
        participantId,
        new SubmitAnswerRequest(runQuestionId, choiceId),
        java.util.Set.of()
    );

    assertThat(response).isNotNull();
    assertThat(response.answeredCount()).isEqualTo(1);

    // 답변 저장 트랜잭션이 잠금 안에서 시작되고 커밋되었는지 검증
    assertThat(TestConfig.eventLog).containsExactly("TX_BEGIN (locked=true)", "TX_COMMIT (locked=true)");
    assertThat(roomLockManager.isLocked(roomId)).isFalse();
  }

  @Test
  @DisplayName("트랜잭션 중 예외 발생 시 TX_ROLLBACK이 수행되고 잠금은 정상 해제된다 (Issue 1)")
  void transaction_rollsBackOnException_andReleasesLock() {
    Long roomId = 42L;
    Long hostUserId = 1L;
    Long unauthorizedUserId = 99L;
    StartQuizRequest request = new StartQuizRequest(7L);

    given(roomPort.getRoom(roomId)).willReturn(new RoomSnapshot(roomId, hostUserId, RoomStatus.OPEN));

    assertThatThrownBy(() -> quizCoreService.startQuiz(roomId, request, unauthorizedUserId))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.FORBIDDEN);

    assertThat(TestConfig.eventLog).containsExactly("TX_BEGIN (locked=true)", "TX_ROLLBACK (locked=true)");
    assertThat(roomLockManager.isLocked(roomId)).isFalse();
  }
}
