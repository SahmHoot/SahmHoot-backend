package com.sahmhoot.room.service;

import com.sahmhoot.quiz.lock.RoomLockManager;
import com.sahmhoot.room.dto.RoomStateResponse;
import com.sahmhoot.room.dto.RoomStateResponse.*;
import com.sahmhoot.room.entity.Room;
import com.sahmhoot.room.entity.RoomStatus;
import com.sahmhoot.room.exception.RoomErrorCode;
import com.sahmhoot.room.exception.RoomException;
import com.sahmhoot.room.repository.RoomParticipantRepository;
import com.sahmhoot.room.repository.RoomRepository;
import com.sahmhoot.room.service.port.RoomQuizStatePort;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class RoomStateService {
  private final RoomRepository rooms;
  private final RoomParticipantRepository participants;
  private final RoomUserLookup users;
  private final RoomLockManager locks;
  private final RoomQuizStatePort quizzes;
  private final Clock clock;
  private final RoomPresenceService presence;
  private final String joinBaseUrl;
  private final TransactionTemplate reading;

  public RoomStateService(RoomRepository rooms, RoomParticipantRepository participants,
      RoomUserLookup users, RoomLockManager locks, RoomQuizStatePort quizzes,
      Clock clock, RoomPresenceService presence, PlatformTransactionManager transactions,
      @Value("${app.room.join-base-url:http://localhost:5173}") String joinBaseUrl) {
    this.rooms = rooms;
    this.participants = participants;
    this.users = users;
    this.locks = locks;
    this.quizzes = quizzes;
    this.clock = clock;
    this.presence = presence;
    this.joinBaseUrl = joinBaseUrl.replaceAll("/+$", "");
    reading = new TransactionTemplate(transactions);
    reading.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    reading.setReadOnly(true);
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public RoomStateResponse state(Long roomId, Long userId) {
    // 방·퀴즈의 상태가 서로 다른 시점에서 읽히지 않도록 잠금 다음에 트랜잭션을 시작한다.
    return locks.executeWithLock(roomId, () -> reading.execute(tx -> read(roomId, userId)));
  }

  private RoomStateResponse read(Long roomId, Long userId) {
    var user = users.findById(userId).orElseThrow(() -> new RoomException(RoomErrorCode.UNAUTHORIZED));
    Room room = rooms.findById(roomId).orElseThrow(() -> new RoomException(RoomErrorCode.ROOM_NOT_FOUND));
    if (room.getStatus() == RoomStatus.CLOSED) throw new RoomException(RoomErrorCode.ROOM_CLOSED);
    var participant = participants.findByRoomIdAndUserIdAndLeftAtIsNull(roomId, userId)
        .orElseThrow(() -> new RoomException(RoomErrorCode.NOT_PARTICIPANT));
    boolean owner = room.getHostId().equals(userId);
    String ownerName = owner ? user.name() : users.findById(room.getHostId())
        .orElseThrow(() -> new RoomException(RoomErrorCode.ROOM_NOT_FOUND)).name();
    var snapshot = Objects.requireNonNull(quizzes.read(roomId, participant.getId()), "Quiz state snapshot is required");
    if (room.getStatus() == RoomStatus.PLAYING && snapshot.quiz() == null) {
      throw new IllegalStateException("PLAYING 상태에 활성 퀴즈 스냅샷이 없습니다.");
    }
    Quiz quiz = room.getStatus() == RoomStatus.OPEN ? null : visibleQuiz(snapshot);
    Progress progress = owner && quiz != null && quiz.phase() == Phase.QUESTION
        ? snapshot.progress() : null;
    if (progress != null && !progress.runQuestionId().equals(quiz.currentQuestion().runQuestionId())) {
      throw new IllegalStateException("응답 집계와 현재 문항이 일치하지 않습니다.");
    }
    if (quiz != null && owner) {
      quiz = new Quiz(quiz.runId(), quiz.questionSetTitle(), quiz.totalQuestions(), quiz.phase(),
          quiz.currentQuestion(), quiz.nextOpensAt(), null, quiz.result());
    }
    String joinUrl = UriComponentsBuilder.fromUriString(joinBaseUrl)
        .pathSegment("join", room.getCode()).build().toUriString();
    int onlineCount = presence.onlineCount(room);
    // 채팅·반응 모듈 연동 전의 빈 값. 접속 인원은 참여 기록 수와 구분한다.
    return new RoomStateResponse(Instant.now(clock), new RoomDetail(roomId, room.getTitle(),
        room.getStatus(), room.getCode(), joinUrl, ownerName), snapshot.quizRun(),
        new Me(participant.getId(), participant.getNickname(), owner, false), onlineCount, quiz,
        null, List.of(), progress, owner ? Map.of() : null);
  }

  private Quiz visibleQuiz(RoomQuizStatePort.Snapshot snapshot) {
    Quiz quiz = snapshot.quiz();
    Phase phase = Objects.requireNonNull(quiz.phase(), "Quiz phase is required");
    RunStatus expected = phase == Phase.RESULT ? RunStatus.FINISHED : RunStatus.RUNNING;
    if (snapshot.quizRun() == null || snapshot.quizRun().status() != expected) {
      throw new IllegalStateException("회차 상태와 화면 단계가 일치하지 않습니다.");
    }
    CurrentQuestion question = null;
    if (phase != Phase.RESULT) {
      var current = Objects.requireNonNull(quiz.currentQuestion(), "Current question is required");
      if (phase == Phase.FEEDBACK && current.closedAt() == null) {
        throw new IllegalStateException("마감되지 않은 문항은 정답을 공개할 수 없습니다.");
      }
      if (phase == Phase.FEEDBACK && quiz.nextOpensAt() == null) {
        throw new IllegalStateException("피드백 종료 예약 시각이 필요합니다.");
      }
      question = new CurrentQuestion(current.runQuestionId(), current.questionNo(), current.questionType(),
          current.timeLimitSeconds(), current.content(), List.copyOf(current.choices()), current.openedAt(),
          current.closesAt(), current.closedAt(), phase == Phase.FEEDBACK ? current.correctChoiceId() : null);
    }
    return new Quiz(quiz.runId(), quiz.questionSetTitle(), quiz.totalQuestions(), phase, question,
        phase == Phase.FEEDBACK ? quiz.nextOpensAt() : null,
        List.copyOf(quiz.myAnswers() == null ? List.of() : quiz.myAnswers()),
        phase == Phase.RESULT ? Objects.requireNonNull(quiz.result(), "Quiz result is required") : null);
  }
}
