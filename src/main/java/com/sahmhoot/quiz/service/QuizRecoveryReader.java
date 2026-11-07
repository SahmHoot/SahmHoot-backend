package com.sahmhoot.quiz.service;

import com.sahmhoot.quiz.entity.QuizRunStatus;
import com.sahmhoot.quiz.entity.RunQuestionStatus;
import com.sahmhoot.quiz.repository.QuizRunRepository;
import com.sahmhoot.quiz.repository.RunQuestionRepository;
import com.sahmhoot.quiz.service.port.RoomPort;
import com.sahmhoot.quiz.service.port.RoomStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 복구 후보와 방 잠금 안에서 재확인한 DB 상태를 읽는다. 예약은 읽기 트랜잭션이 끝난 뒤 등록한다. */
@Service
@RequiredArgsConstructor
public class QuizRecoveryReader {
  private final QuizRunRepository runs;
  private final RunQuestionRepository questions;
  private final RoomPort rooms;

  @Transactional(readOnly = true)
  public List<RunningQuiz> findRunningQuizzes() {
    return runs.findByStatus(QuizRunStatus.RUNNING).stream()
        .map(run -> new RunningQuiz(run.getRoomId(), run.getId())).toList();
  }

  @Transactional(readOnly = true)
  public Optional<Snapshot> read(RunningQuiz candidate) {
    var room = rooms.findRoom(candidate.roomId());
    if (room.isEmpty() || room.get().status() != RoomStatus.PLAYING) return Optional.empty();

    var latest = runs.findTopByRoomIdOrderByIdDesc(candidate.roomId());
    if (latest.isEmpty() || !latest.get().getId().equals(candidate.runId())
        || !latest.get().isRunning()) return Optional.empty();

    var recoveryQuestions = questions.findByQuizRunIdOrderByOrderNoAsc(candidate.runId()).stream()
        .map(question -> new Question(question.getId(), question.getOrderNo(), question.getStatus(),
            question.getClosesAt(), question.getClosedAt())).toList();
    return Optional.of(new Snapshot(candidate.roomId(), candidate.runId(), recoveryQuestions));
  }

  public record RunningQuiz(Long roomId, Long runId) {}

  public record Snapshot(Long roomId, Long runId, List<Question> questions) {
    public Snapshot { questions = List.copyOf(questions); }
  }

  public record Question(Long id, int orderNo, RunQuestionStatus status,
      Instant closesAt, Instant closedAt) {}
}
