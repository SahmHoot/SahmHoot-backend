package com.sahmhoot.quiz.service;

import com.sahmhoot.questionimage.dto.RunQuestionImage;
import com.sahmhoot.questionset.service.port.QuestionImageAccess.ImageAccessException;
import com.sahmhoot.questionset.service.port.QuestionImageAccess.Metadata;
import com.sahmhoot.quiz.entity.RunQuestion;
import com.sahmhoot.quiz.entity.RunQuestionStatus;
import com.sahmhoot.quiz.exception.BusinessException;
import com.sahmhoot.quiz.exception.ErrorCode;
import com.sahmhoot.quiz.repository.QuizRunRepository;
import com.sahmhoot.quiz.repository.RunQuestionRepository;
import com.sahmhoot.quiz.service.port.RoomLockPort;
import com.sahmhoot.quiz.service.port.RoomPort;
import com.sahmhoot.quiz.service.port.RoomStatus;
import com.sahmhoot.quiz.service.port.RunImageAccess;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class RunQuestionImageService {
  public static final String PATH = "/api/rooms/{roomId}/quiz-runs/{runId}/questions/{runQuestionId}/image";

  public record Download(String mimeType, byte[] content) {}

  private final RoomPort rooms;
  private final RoomLockPort locks;
  private final QuizRunRepository runs;
  private final RunQuestionRepository questions;
  private final RunImageAccess images;
  private final TransactionTemplate reading;

  public RunQuestionImageService(RoomPort rooms, RoomLockPort locks, QuizRunRepository runs,
      RunQuestionRepository questions, RunImageAccess images, PlatformTransactionManager transactions) {
    this.rooms = rooms;
    this.locks = locks;
    this.runs = runs;
    this.questions = questions;
    this.images = images;
    this.reading = new TransactionTemplate(transactions);
    this.reading.setReadOnly(true);
  }

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public Download download(long roomId, long runId, long questionId, long userId) {
    Metadata metadata = locks.executeWithLock(roomId,
        () -> reading.execute(tx -> authorize(roomId, runId, questionId, userId)));
    // Authorization is evaluated at the snapshot above. Storage I/O holds neither lock nor DB transaction.
    try {
      return new Download(metadata.mimeType(), images.read(metadata.key()));
    } catch (ImageAccessException error) {
      throw new BusinessException(switch (error.failure()) {
        case MISSING -> ErrorCode.QUESTION_IMAGE_NOT_FOUND;
        case NOT_AVAILABLE -> ErrorCode.QUESTION_IMAGE_NOT_AVAILABLE;
        case TEMPORARY_STORAGE -> ErrorCode.IMAGE_STORAGE_UNAVAILABLE;
        default -> ErrorCode.INTERNAL_ERROR;
      });
    }
  }

  RunQuestionImage currentImage(long roomId, long runId, long questionId) {
    return displayImages(roomId, runId, questionId).apply(questionId);
  }

  Function<Long, RunQuestionImage> resultImages(long roomId, long runId) {
    return displayImages(roomId, runId, null);
  }

  private Function<Long, RunQuestionImage> displayImages(long roomId, long runId, Long questionId) {
    if (!images.enabled()) return id -> RunQuestionImage.DISABLED;
    Map<Long, Metadata> metadata = images.runImages(runId, questionId);
    return id -> {
      Metadata image = metadata.get(id);
      return image == null ? null : new RunQuestionImage(
          UriComponentsBuilder.fromPath(PATH).buildAndExpand(roomId, runId, id).toUriString(),
          image.mimeType(), image.width(), image.height());
    };
  }

  private Metadata authorize(long roomId, long runId, long questionId, long userId) {
    var room = rooms.findRoom(roomId).orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
    if (room.status() == RoomStatus.CLOSED) throw new BusinessException(ErrorCode.ROOM_CLOSED);
    rooms.findParticipantId(roomId, userId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_PARTICIPANT));

    var run = runs.findTopByRoomIdOrderByIdDesc(roomId)
        .filter(latest -> latest.getId().equals(runId) && room.status() == RoomStatus.PLAYING
            && (latest.isRunning() || latest.isFinished()))
        .orElseThrow(() -> new BusinessException(ErrorCode.QUIZ_RUN_NOT_FOUND));
    if (run.isRunning()) {
      var current = questions.findFirstByQuizRunIdAndStatus(runId, RunQuestionStatus.OPEN)
          .or(() -> questions.findFirstByQuizRunIdAndStatusOrderByOrderNoDesc(runId, RunQuestionStatus.CLOSED))
          .orElse(null);
      if (current == null || !current.getId().equals(questionId)) {
        throw new BusinessException(ErrorCode.QUESTION_IMAGE_NOT_FOUND);
      }
    }
    var metadata = images.runImages(runId, questionId).get(questionId);
    if (metadata == null) throw new BusinessException(ErrorCode.QUESTION_IMAGE_NOT_FOUND);
    return metadata;
  }

  static RunQuestion currentQuestion(List<RunQuestion> questions) {
    return questions.stream().filter(RunQuestion::isOpen).findFirst()
        .orElseGet(() -> questions.reversed().stream().filter(RunQuestion::isClosed).findFirst().orElse(null));
  }
}
