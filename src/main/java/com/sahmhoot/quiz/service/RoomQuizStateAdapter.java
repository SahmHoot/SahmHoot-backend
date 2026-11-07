package com.sahmhoot.quiz.service;

import com.sahmhoot.quiz.entity.QuizRun;
import com.sahmhoot.quiz.entity.RunChoice;
import com.sahmhoot.quiz.entity.RunQuestion;
import com.sahmhoot.quiz.repository.AnswerRepository;
import com.sahmhoot.quiz.repository.QuizRunRepository;
import com.sahmhoot.quiz.repository.RunChoiceRepository;
import com.sahmhoot.quiz.repository.RunQuestionRepository;
import com.sahmhoot.quiz.service.port.QuestionSetPort;
import com.sahmhoot.quiz.service.port.RoomPort;
import com.sahmhoot.quiz.service.port.RoomStatus;
import com.sahmhoot.room.dto.RoomStateResponse;
import com.sahmhoot.room.dto.RoomStateResponse.*;
import com.sahmhoot.room.service.port.RoomQuizStatePort;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** room이 정의한 조회 계약의 구현. 퀴즈 데이터 조회는 quiz 모듈 안에서만 수행한다. */
@Service
@RequiredArgsConstructor
public class RoomQuizStateAdapter implements RoomQuizStatePort {
  private final QuizRunRepository runs;
  private final RunQuestionRepository questions;
  private final RunChoiceRepository choices;
  private final AnswerRepository answers;
  private final QuestionSetPort sets;
  private final RoomPort rooms;
  private final QuizResultService results;
  private final QuizFeedbackState feedback;

  @Override
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public Snapshot read(Long roomId, Long participantId) {
    var latest = runs.findTopByRoomIdOrderByIdDesc(roomId);
    if (latest.isEmpty()) return Snapshot.waiting();
    QuizRun run = latest.get();
    var status = new RoomStateResponse.QuizRun(RunStatus.valueOf(run.getStatus().name()));
    // 결과 닫기·중단 이후에는 과거 회차 상태만 주고 문항·답·결과를 다시 노출하지 않는다.
    var room = rooms.findRoom(roomId).orElseThrow(() -> new IllegalStateException("조회할 방이 없습니다."));
    if (room.status() != RoomStatus.PLAYING || (!run.isRunning() && !run.isFinished())) {
      return new Snapshot(status, null, null);
    }
    List<RunQuestion> allQuestions = questions.findByQuizRunIdOrderByOrderNoAsc(run.getId());
    String title = sets.findQuestionSet(run.getQuestionSetId())
        .orElseThrow(() -> new IllegalStateException("회차의 문제집이 없습니다.")).title();
    var myAnswers = answers.findByQuizRunIdAndParticipantId(run.getId(), participantId).stream()
        .map(answer -> new MyAnswer(answer.getRunQuestion().getId(), answer.getRunChoice().getId())).toList();
    if (run.isFinished()) {
      return new Snapshot(status, new Quiz(run.getId(), title, allQuestions.size(), Phase.RESULT,
          null, null, myAnswers, result(run.getId())), null);
    }
    var open = allQuestions.stream().filter(RunQuestion::isOpen).findFirst();
    Phase phase = open.isPresent() ? Phase.QUESTION : Phase.FEEDBACK;
    RunQuestion current = open.orElseGet(() -> allQuestions.reversed().stream()
        .filter(RunQuestion::isClosed).findFirst()
        .orElseThrow(() -> new IllegalStateException("진행 중인 회차에 현재 문항이 없습니다.")));
    List<RunChoice> currentChoices = choices.findByRunQuestionIdOrderByOrderNoAsc(current.getId());
    Long correctChoiceId = phase == Phase.FEEDBACK ? currentChoices.stream().filter(RunChoice::isCorrect)
        .map(RunChoice::getId).findFirst().orElseThrow() : null;
    Instant nextOpensAt = phase == Phase.FEEDBACK
        ? feedback.nextOpensAt(roomId, run.getId(), current.getId())
            .orElseGet(() -> current.getClosedAt().plusSeconds(3)) : null;
    var question = new CurrentQuestion(current.getId(), allQuestions.indexOf(current) + 1,
        current.getType().name(), current.getTimeLimitSeconds(), current.getContent(),
        currentChoices.stream().map(choice -> new Choice(choice.getId(), choice.getOrderNo(), choice.getContent())).toList(),
        current.getOpenedAt(), current.getClosesAt(), current.getClosedAt(), correctChoiceId);
    var progress = phase == Phase.QUESTION
        ? new Progress(current.getId(), Math.toIntExact(answers.countByRunQuestionId(current.getId()))) : null;
    return new Snapshot(status, new Quiz(run.getId(), title, allQuestions.size(), phase,
        question, nextOpensAt, myAnswers, null), progress);
  }

  private Result result(Long runId) {
    return new Result(results.aggregateResult(runId).questions().stream().map(question -> new QuestionResult(
        question.runQuestionId(), question.questionNo(), question.content(), question.correctChoiceId(),
        question.answeredCount(), question.correctCount(), question.incorrectCount(),
        question.choices().stream().map(choice -> new ChoiceResult(
            choice.id(), choice.orderNo(), choice.content(), choice.count())).toList())).toList());
  }
}
