package com.sahmhoot.quiz.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahmhoot.quiz.dto.StartQuizRequest;
import com.sahmhoot.quiz.dto.SubmitAnswerRequest;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

class QuizCoreTransactionContractTest {

  @Test
  @DisplayName("QuizCoreTxService는 @Service 빈이고 핵심 변경 메서드에 @Transactional이 적용되어 있다 (Issue 1)")
  void quizCoreTxService_methodsAreTransactional() throws NoSuchMethodException {
    assertThat(QuizCoreTxService.class.getAnnotation(Service.class)).isNotNull();

    Method startQuiz = QuizCoreTxService.class.getMethod("startQuiz", Long.class, StartQuizRequest.class, Long.class);
    assertThat(startQuiz.getAnnotation(Transactional.class)).isNotNull();

    Method abortQuiz = QuizCoreTxService.class.getMethod("abortQuiz", Long.class, Long.class, Long.class);
    assertThat(abortQuiz.getAnnotation(Transactional.class)).isNotNull();

    Method closeResult = QuizCoreTxService.class.getMethod("closeResult", Long.class, Long.class);
    assertThat(closeResult.getAnnotation(Transactional.class)).isNotNull();

    Method closeQuestion = QuizCoreTxService.class.getMethod("closeQuestion", Long.class, Long.class);
    assertThat(closeQuestion.getAnnotation(Transactional.class)).isNotNull();

    Method finishQuiz = QuizCoreTxService.class.getMethod("finishQuiz", Long.class, Long.class);
    assertThat(finishQuiz.getAnnotation(Transactional.class)).isNotNull();

    Method openQuestion = QuizCoreTxService.class.getMethod("openQuestion", Long.class, Long.class, int.class);
    assertThat(openQuestion.getAnnotation(Transactional.class)).isNotNull();
  }

  @Test
  @DisplayName("AnswerTxService는 @Service 빈이고 submitAnswer에 @Transactional이 적용되어 있다 (Issue 1)")
  void answerTxService_methodsAreTransactional() throws NoSuchMethodException {
    assertThat(AnswerTxService.class.getAnnotation(Service.class)).isNotNull();

    Method submitAnswer = AnswerTxService.class.getMethod(
        "submitAnswer",
        Long.class,
        Long.class,
        SubmitAnswerRequest.class,
        java.util.Set.class
    );
    assertThat(submitAnswer.getAnnotation(Transactional.class)).isNotNull();
  }

  @Test
  @DisplayName("QuizCoreService와 AnswerService는 자기 호출(Self-Invocation)이 아닌 분리된 Tx 서비스를 주입받는다 (Issue 1)")
  void coordinators_injectSeparateTxServices() {
    // QuizCoreService 필드 중 QuizCoreTxService가 존재하는지 확인
    boolean hasTxService = false;
    for (var field : QuizCoreService.class.getDeclaredFields()) {
      if (field.getType().equals(QuizCoreTxService.class)) {
        hasTxService = true;
        break;
      }
    }
    assertThat(hasTxService).as("QuizCoreService must inject QuizCoreTxService").isTrue();

    // AnswerService 필드 중 AnswerTxService가 존재하는지 확인
    boolean hasAnswerTxService = false;
    for (var field : AnswerService.class.getDeclaredFields()) {
      if (field.getType().equals(AnswerTxService.class)) {
        hasAnswerTxService = true;
        break;
      }
    }
    assertThat(hasAnswerTxService).as("AnswerService must inject AnswerTxService").isTrue();
  }
}
