package com.sahmhoot.questionimage.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public interface QuestionImageLifecycleRepository {
  record Reservation(UUID key, long uploaderId, long questionSetId, String mimeType, int size,
                     int width, int height, Instant createdAt, Instant expiresAt) {}

  boolean canUpload(long uploaderId, long questionSetId);

  // Atomically verify professor role and set ownership and commit an UPLOADING reservation.
  boolean reserve(Reservation reservation);

  // Lock the live reservation until the write finishes. Commit AVAILABLE when the callback
  // returns true, discard the reservation on a confirmed collision (false), and retain it
  // for cleanup if the callback throws.
  boolean finishUpload(UUID key, Instant now, BooleanSupplier write);

  boolean attachToQuestion(UUID key, long uploaderId, long questionSetId, long questionId, Instant now);

  List<UUID> cleanupCandidates(Instant now, int limit);

  // Attachment and snapshot writers must use the same image lock and require AVAILABLE.
  // With the image row locked, check both question tables using current reads and commit
  // DELETE_PENDING only for an expired image with neither reference.
  boolean claimDeletion(UUID key, Instant now);

  // Lock the claimed row, recheck both references, and remove metadata only after delete
  // succeeds. A thrown exception leaves DELETE_PENDING available for another attempt.
  void finishDeletion(UUID key, Runnable delete);
}
