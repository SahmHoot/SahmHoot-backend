package com.sahmhoot.questionimage.service;

import com.sahmhoot.questionimage.service.QuestionImageLifecycleRepository.Reservation;
import com.sahmhoot.questionimage.storage.ImageStorage;
import com.sahmhoot.questionimage.storage.ImageStorageException;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.security.access.AccessDeniedException;

public final class QuestionImageLifecycle {
  private static final Duration TEMPORARY_LIFETIME = Duration.ofHours(24);

  private final QuestionImageValidator validator;
  private final QuestionImageLifecycleRepository repository;
  private final ImageStorage storage;
  private final Clock clock;
  private final Semaphore storageMutations;

  public QuestionImageLifecycle(QuestionImageValidator validator, QuestionImageLifecycleRepository repository,
      ImageStorage storage, Clock clock, int maxStorageMutations) {
    this.validator = validator;
    this.repository = repository;
    this.storage = storage;
    this.clock = clock;
    this.storageMutations = new Semaphore(maxStorageMutations);
  }

  public UUID upload(long uploaderId, long questionSetId, InputStream source, String mimeType) throws IOException {
    return uploadImage(uploaderId, questionSetId, source, mimeType).key();
  }

  public Reservation uploadImage(long uploaderId, long questionSetId,
      InputStream source, String mimeType) throws IOException {
    // Acquire before any DB work. Slow storage writes otherwise hold a pooled connection
    // until the guarded row transition completes, so excess uploads must return promptly.
    if (!storageMutations.tryAcquire()) {
      throw new ImageStorageException(ImageStorageException.Reason.UNAVAILABLE);
    }
    try {
      return uploadWithPermit(uploaderId, questionSetId, source, mimeType);
    } finally {
      storageMutations.release();
    }
  }

  private Reservation uploadWithPermit(long uploaderId, long questionSetId, InputStream source, String mimeType)
      throws IOException {
    if (!repository.canUpload(uploaderId, questionSetId)) {
      throw new AccessDeniedException("Question set image access denied");
    }
    var image = validator.validate(source, mimeType);
    var now = clock.instant();
    var reservation = new Reservation(image.key(), uploaderId, questionSetId,
        image.mimeType(), image.content().length, image.width(), image.height(), now, now.plus(TEMPORARY_LIFETIME));
    if (!repository.reserve(reservation)) {
      throw new AccessDeniedException("Question set image access denied");
    }
    if (!repository.finishUpload(image.key(), clock.instant(), () -> save(image))) {
      throw new UploadNotAvailableException();
    }
    return reservation;
  }

  static class UploadNotAvailableException extends IllegalStateException {}

  public List<UUID> cleanExpired(int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("Image cleanup limit must be positive");
    }
    var now = clock.instant();
    List<UUID> failed = new ArrayList<>();
    for (UUID key : repository.cleanupCandidates(now, limit)) {
      // Leave unclaimed rows eligible for the next scheduled pass under contention.
      if (!storageMutations.tryAcquire()) break;
      try {
        if (repository.claimDeletion(key, now)) {
          repository.finishDeletion(key, () -> storage.delete(key));
        }
      } catch (ImageStorageException | TransientDataAccessException e) {
        failed.add(key);
      } finally {
        storageMutations.release();
      }
    }
    return List.copyOf(failed);
  }

  public boolean attachToQuestion(UUID key, long uploaderId, long questionSetId, long questionId) {
    return repository.attachToQuestion(key, uploaderId, questionSetId, questionId, clock.instant());
  }

  private boolean save(QuestionImageValidator.ValidatedImage image) {
    try {
      storage.save(image.key(), image.mimeType(), image.content());
      return true;
    } catch (ImageStorageException e) {
      if (e.reason() == ImageStorageException.Reason.CONFLICT) {
        return false;
      }
      throw e;
    }
  }
}
