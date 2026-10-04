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

    if (!question.getQuizRun().getRoomId().equals(roomId)) {
      throw new BusinessException(ErrorCode.QUESTION_NOT_FOUND);
    }

    if (!question.getQuizRun().isRunning()) {
      throw new BusinessException(ErrorCode.QUESTION_CLOSED);
    }

    Instant now = Instant.now();
    // 06 규격 5절: 허용 조건 = closed_at 없음 AND 서버 시각 <= closes_at + 1초
    Instant maxAllowed = question.getClosesAt() != null ? question.getClosesAt().plusSeconds(1) : now;
    if (!question.isOpen() || question.getClosedAt() != null || now.isAfter(maxAllowed)) {
      throw new BusinessException(ErrorCode.QUESTION_CLOSED);
    }

    RunChoice choice = runChoiceRepository.findByIdAndRunQuestionId(request.choiceId(), question.getId())
        .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED, "해당 문항의 선택지가 아닙니다."));

    Optional<Answer> existingAnswer = answerRepository.findByRunQuestionIdAndParticipantId(
        question.getId(),
        participantId
    );

    if (existingAnswer.isPresent()) {
      Answer answer = existingAnswer.get();
      answer.updateChoice(choice, now);
      answerRepository.save(answer);
      log.info("Updated answer: participantId={}, runQuestionId={}, choiceId={}", participantId, question.getId(), choice.getId());
    } else {
      Answer newAnswer = Answer.builder()
          .runQuestion(question)
          .participantId(participantId)
          .runChoice(choice)
          .submittedAt(now)
          .updatedAt(now)
          .build();
      answerRepository.save(newAnswer);
      log.info("Saved new answer: participantId={}, runQuestionId={}, choiceId={}", participantId, question.getId(), choice.getId());
    }

    long answeredCount = answerRepository.countByRunQuestionId(question.getId());

    // 전체 답변 수에는 이미 접속을 끊은 참가자도 들어갈 수 있으므로 접속 중인 참가자의 답만 센다.
    boolean shouldClose = false;
    if (!onlineParticipantIds.isEmpty()) {
      long onlineAnsweredCount = answerRepository.countByRunQuestionIdAndParticipantIdIn(
          question.getId(), onlineParticipantIds);
      shouldClose = onlineAnsweredCount == onlineParticipantIds.size();
    }

    return new SubmitAnswerTxResult(question.getId(), (int) answeredCount, shouldClose);
  }

  @Transactional(readOnly = true)
  public long countAnswered(Long runQuestionId) {
    return answerRepository.countByRunQuestionId(runQuestionId);
  }
}
