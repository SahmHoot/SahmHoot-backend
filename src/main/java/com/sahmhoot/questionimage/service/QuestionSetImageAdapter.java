package com.sahmhoot.questionimage.service;

import com.sahmhoot.questionimage.storage.ImageStorageException;
import org.springframework.security.access.AccessDeniedException;
import com.sahmhoot.questionset.service.port.QuestionImageAccess;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
class QuestionSetImageAdapter implements QuestionImageAccess {
  private final ObjectProvider<QuestionImageLifecycle> lifecycle;

  QuestionSetImageAdapter(ObjectProvider<QuestionImageLifecycle> lifecycle) {
    this.lifecycle = lifecycle;
  }

  @Override
  public boolean enabled() {
    return lifecycle.getIfAvailable() != null;
  }

  @Override
  public Upload upload(long uploaderId, long setId, InputStream file, String mimeType) throws IOException {
    try {
      var saved = lifecycle.getObject().uploadImage(uploaderId, setId, file, mimeType);
      return new Upload(saved.key(), saved.mimeType(), saved.size(), saved.width(), saved.height(), saved.expiresAt());
    } catch (InvalidImageException e) {
      throw new ImageAccessException(switch (e.reason()) {
        case INVALID -> Failure.INVALID;
        case DIMENSIONS -> Failure.DIMENSIONS;
        case TOO_LARGE -> Failure.TOO_LARGE;
        case UNSUPPORTED -> Failure.UNSUPPORTED;
      });
    } catch (QuestionImageLifecycle.UploadNotAvailableException e) {
      throw new ImageAccessException(Failure.NOT_AVAILABLE);
    } catch (AccessDeniedException e) {
      throw new ImageAccessException(Failure.FORBIDDEN);
    } catch (ImageStorageException e) {
      throw storageFailure(e);
    }
  }

  private ImageAccessException storageFailure(ImageStorageException error) {
    return new ImageAccessException(switch (error.reason()) {
      case MISSING -> Failure.MISSING;
      case UNAVAILABLE -> Failure.TEMPORARY_STORAGE;
      default -> Failure.STORAGE;
    });
  }
}
