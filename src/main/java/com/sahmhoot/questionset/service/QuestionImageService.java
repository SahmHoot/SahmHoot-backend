package com.sahmhoot.questionset.service;

import com.sahmhoot.questionset.auth.QuestionSetCurrentUser;
import com.sahmhoot.questionset.exception.QuestionSetErrorCode;
import com.sahmhoot.questionset.exception.QuestionSetException;
import com.sahmhoot.questionset.service.port.QuestionImageAccess;
import com.sahmhoot.questionset.service.port.QuestionImageAccess.ImageAccessException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.List;
import com.sahmhoot.questionset.exception.QuestionSetErrorResponse.FieldError;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class QuestionImageService {
  public record Download(String mimeType, byte[] content) {}

  private final QuestionSetService sets;
  private final QuestionImageAccess images;

  public QuestionImageAccess.Upload upload(long setId, QuestionSetCurrentUser user, InputStream file, String mimeType) {
    requireEnabled();
    if (!user.professor() || !Objects.equals(sets.readableSet(setId, user.id()).getHostId(), user.id())) {
      throw new QuestionSetException(QuestionSetErrorCode.FORBIDDEN);
    }
    try {
      return images.upload(user.id(), setId, file, mimeType);
    } catch (ImageAccessException e) {
      throw translate(e);
    } catch (IOException e) {
      throw new QuestionSetException(QuestionSetErrorCode.INTERNAL_ERROR);
    }
  }

  public Download download(long setId, long questionId, long userId) {
    requireEnabled();
    var metadata = sets.readableImage(setId, questionId, userId);
    try {
      return new Download(metadata.mimeType(), images.read(metadata.key()));
    } catch (ImageAccessException e) {
      throw translate(e);
    }
  }

  private void requireEnabled() {
    if (!images.enabled()) throw new QuestionSetException(QuestionSetErrorCode.QUESTION_NOT_FOUND);
  }

  static QuestionSetException translate(ImageAccessException exception) {
    var code = switch (exception.failure()) {
      case INVALID, DIMENSIONS -> QuestionSetErrorCode.VALIDATION_FAILED;
      case TOO_LARGE -> QuestionSetErrorCode.IMAGE_TOO_LARGE;
      case UNSUPPORTED -> QuestionSetErrorCode.UNSUPPORTED_IMAGE_FORMAT;
      case FORBIDDEN -> QuestionSetErrorCode.FORBIDDEN;
      case MISSING -> QuestionSetErrorCode.QUESTION_IMAGE_NOT_FOUND;
      case NOT_AVAILABLE -> QuestionSetErrorCode.QUESTION_IMAGE_NOT_AVAILABLE;
      case TEMPORARY_STORAGE -> QuestionSetErrorCode.IMAGE_STORAGE_UNAVAILABLE;
      case STORAGE -> QuestionSetErrorCode.INTERNAL_ERROR;
    };
    var fields = exception.failure() == QuestionImageAccess.Failure.DIMENSIONS
        ? List.of(new FieldError("file", "이미지는 양변 4096px, 총 1600만 픽셀 이하여야 합니다.")) : null;
    return new QuestionSetException(code, fields);
  }
}
