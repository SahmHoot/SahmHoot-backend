package com.sahmhoot.questionset.service.port;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public interface QuestionImageAccess {
  record Metadata(UUID key, String mimeType, int width, int height) {}
  record Upload(UUID imageRef, String mimeType, int sizeBytes, int width, int height, Instant expiresAt) {}

  enum Failure { INVALID, DIMENSIONS, TOO_LARGE, UNSUPPORTED, FORBIDDEN, MISSING, NOT_AVAILABLE, TEMPORARY_STORAGE, STORAGE }

  class ImageAccessException extends RuntimeException {
    private final Failure failure;

    public ImageAccessException(Failure failure) {
      this.failure = failure;
    }

    public Failure failure() {
      return failure;
    }
  }

  boolean enabled();

  Upload upload(long uploaderId, long setId, InputStream file, String mimeType) throws IOException;
}
