package com.sahmhoot.questionimage.service;

import com.sahmhoot.questionimage.storage.ImageStorage;
import com.sahmhoot.questionimage.storage.ImageStorageException;
import org.springframework.security.access.AccessDeniedException;
import com.sahmhoot.questionset.service.port.QuestionImageAccess;
import com.sahmhoot.quiz.service.port.RunImageAccess;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class QuestionSetImageAdapter implements QuestionImageAccess, RunImageAccess {
  private final ObjectProvider<QuestionImageLifecycle> lifecycle;
  private final ObjectProvider<ImageStorage> storage;
  private final JdbcTemplate jdbc;

  QuestionSetImageAdapter(ObjectProvider<QuestionImageLifecycle> lifecycle,
      ObjectProvider<ImageStorage> storage, JdbcTemplate jdbc) {
    this.lifecycle = lifecycle;
    this.storage = storage;
    this.jdbc = jdbc;
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

  @Override
  public Map<Long, Metadata> images(long setId) {
    return findImages(setId, null);
  }

  @Override
  public Metadata image(long setId, long questionId) {
    return findImages(setId, questionId).get(questionId);
  }

  private Map<Long, Metadata> findImages(long setId, Long questionId) {
    String sql = """
        SELECT q.id, i.image_key, i.mime_type, i.width, i.height
        FROM questions q JOIN question_images i ON i.image_key = q.image_key
        WHERE q.question_set_id = ? AND i.status = 'AVAILABLE'
        """;
    Object[] parameters = questionId == null ? new Object[] {setId} : new Object[] {setId, questionId};
    return queryImages(sql, questionId, parameters);
  }

  @Override
  public Map<Long, Metadata> runImages(long runId, Long questionId) {
    return queryImages("""
        SELECT q.id, i.image_key, i.mime_type, i.width, i.height
        FROM run_questions q JOIN question_images i ON i.image_key = q.image_key
        WHERE q.quiz_run_id = ? AND i.status = 'AVAILABLE'
        """, questionId, questionId == null ? new Object[] {runId} : new Object[] {runId, questionId});
  }

  private Map<Long, Metadata> queryImages(String sql, Long questionId, Object... parameters) {
    if (questionId != null) sql += " AND q.id = ?";
    return jdbc.query(sql, rows -> {
          Map<Long, Metadata> images = new java.util.HashMap<>();
          while (rows.next()) {
            images.put(rows.getLong(1), new Metadata(UUID.fromString(rows.getString(2)), rows.getString(3),
                rows.getInt(4), rows.getInt(5)));
          }
          return images;
        }, parameters);
  }

  @Override
  public byte[] read(UUID key) {
    try {
      return storage.getObject().read(key);
    } catch (ImageStorageException e) {
      throw storageFailure(e);
    }
  }

  @Override
  public void attach(UUID key, long uploaderId, long setId, long questionId) {
    if (lifecycle.getObject().attachToQuestion(key, uploaderId, setId, questionId)) return;
    boolean foreign = !jdbc.queryForList("""
        SELECT 1 FROM question_images WHERE image_key = ? AND (uploader_id <> ? OR question_set_id <> ?)
        """, Integer.class, key.toString(), uploaderId, setId).isEmpty();
    throw new ImageAccessException(foreign ? Failure.FORBIDDEN : Failure.NOT_AVAILABLE);
  }

  private ImageAccessException storageFailure(ImageStorageException error) {
    return new ImageAccessException(switch (error.reason()) {
      case MISSING -> Failure.MISSING;
      case UNAVAILABLE -> Failure.TEMPORARY_STORAGE;
      default -> Failure.STORAGE;
    });
  }

  @Override
  public void detach(long setId, long questionId) {
    jdbc.update("UPDATE questions SET image_key = NULL WHERE id = ? AND question_set_id = ?", questionId, setId);
  }
}
