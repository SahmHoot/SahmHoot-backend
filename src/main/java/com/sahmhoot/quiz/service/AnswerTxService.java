package com.sahmhoot.quiz.service;

import com.sahmhoot.quiz.exception.BusinessException;
import com.sahmhoot.quiz.exception.ErrorCode;
import com.sahmhoot.quiz.dto.SubmitAnswerRequest;
import com.sahmhoot.quiz.entity.Answer;
import com.sahmhoot.quiz.entity.RunChoice;
import com.sahmhoot.quiz.entity.RunQuestion;
import com.sahmhoot.quiz.repository.AnswerRepository;
import com.sahmhoot.quiz.repository.RunChoiceRepository;
import com.sahmhoot.quiz.repository.RunQuestionRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 5. Answer 도메인의 트랜잭션 전담 서비스.
 * Spring AOP 프록시를 통해 @Transactional 경계를 정상 보장하며,
 * RoomLockManager 잠금 안에서 호출되어 "잠금 -> 트랜잭션 커밋 -> 잠금 해제" 구조를 달성한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnswerTxService {

  private final AnswerRepository answerRepository;
  private final RunQuestionRepository runQuestionRepository;
  private final RunChoiceRepository runChoiceRepository;

  /**
   * 답변 제출/수정 트랜잭션.
   */
  @Transactional
  public SubmitAnswerTxResult submitAnswer(
      Long roomId,
      Long participantId,
      SubmitAnswerRequest request,
      Set<Long> onlineParticipantIds
  ) {
    RunQuestion question = runQuestionRepository.findById(request.runQuestionId())
        .orElseThrow(() -> new BusinessException(ErrorCode.QUESTION_NOT_FOUND));

    Instant now = Instant.now();
    validateAnswerWindow(question, roomId, now);

    RunChoice choice = runChoiceRepository.findByIdAndRunQuestionId(request.choiceId(), question.getId())
        .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED, "해당 문항의 선택지가 아닙니다."));

    saveOrUpdateAnswer(question, participantId, choice, now);

    long answeredCount = answerRepository.countByRunQuestionId(question.getId());
    boolean shouldClose = hasEveryOnlineParticipantAnswered(question.getId(), onlineParticipantIds);

    return new SubmitAnswerTxResult(question.getId(), (int) answeredCount, shouldClose);
  }

  @Transactional(readOnly = true)
  public long countAnswered(Long runQuestionId) {
    return answerRepository.countByRunQuestionId(runQuestionId);
  }

  private void validateAnswerWindow(RunQuestion question, Long roomId, Instant now) {
    if (!question.getQuizRun().getRoomId().equals(roomId)) {
      throw new BusinessException(ErrorCode.QUESTION_NOT_FOUND);
    }
    if (!question.getQuizRun().isRunning()) {
      throw new BusinessException(ErrorCode.QUESTION_CLOSED);
    }

    // 06 규격 5절: closed_at이 없고 서버 시각이 closes_at + 1초 이내여야 한다.
    Instant lastAcceptedAt = question.getClosesAt() != null ? question.getClosesAt().plusSeconds(1) : now;
    if (!question.isOpen() || question.getClosedAt() != null || now.isAfter(lastAcceptedAt)) {
      throw new BusinessException(ErrorCode.QUESTION_CLOSED);
    }
  }

  private boolean hasEveryOnlineParticipantAnswered(Long runQuestionId, Set<Long> onlineParticipantIds) {
    if (onlineParticipantIds.isEmpty()) {
      return false;
    }

    // 전체 답변 수에는 접속을 끊은 참가자도 포함될 수 있다.
    long onlineAnsweredCount = answerRepository.countByRunQuestionIdAndParticipantIdIn(
        runQuestionId, onlineParticipantIds);
    return onlineAnsweredCount == onlineParticipantIds.size();
  }

  private void saveOrUpdateAnswer(RunQuestion question, Long participantId, RunChoice choice, Instant now) {
    Optional<Answer> existingAnswer = answerRepository.findByRunQuestionIdAndParticipantId(
        question.getId(), participantId);
    if (existingAnswer.isPresent()) {
      Answer answer = existingAnswer.get();
      answer.updateChoice(choice, now);
      answerRepository.save(answer);
      log.info("Updated answer: participantId={}, runQuestionId={}, choiceId={}",
          participantId, question.getId(), choice.getId());
      return;
    }

    Answer newAnswer = Answer.builder()
        .runQuestion(question)
        .participantId(participantId)
        .runChoice(choice)
        .submittedAt(now)
        .updatedAt(now)
        .build();
    answerRepository.save(newAnswer);
    log.info("Saved new answer: participantId={}, runQuestionId={}, choiceId={}",
        participantId, question.getId(), choice.getId());
  }
}
