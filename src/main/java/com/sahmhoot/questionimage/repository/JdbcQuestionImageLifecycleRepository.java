package com.sahmhoot.questionimage.repository;

import com.sahmhoot.questionimage.service.QuestionImageLifecycleRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcQuestionImageLifecycleRepository implements QuestionImageLifecycleRepository {
  private static final String UNREFERENCED = """
      NOT EXISTS (SELECT 1 FROM questions q WHERE q.image_key = i.image_key)
      AND NOT EXISTS (SELECT 1 FROM run_questions r WHERE r.image_key = i.image_key)
      """;

  private final JdbcTemplate jdbc;
  private final TransactionTemplate independent;
  private final TransactionTemplate attachment;

  public JdbcQuestionImageLifecycleRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    independent = new TransactionTemplate(manager);
    independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    independent.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    attachment = new TransactionTemplate(manager);
  }

  @Override
  public boolean canUpload(long uploaderId, long questionSetId) {
    return ownsSet(uploaderId, questionSetId, false);
  }

  @Override
  public boolean reserve(Reservation image) {
    return independent.execute(transaction -> {
      if (!ownsSet(image.uploaderId(), image.questionSetId(), true)) {
        return false;
      }
      jdbc.update("""
          INSERT INTO question_images
            (image_key, uploader_id, question_set_id, status, mime_type, size_bytes, width, height,
             created_at, expires_at, cleanup_after)
          VALUES (?, ?, ?, 'UPLOADING', ?, ?, ?, ?, ?, ?, ?)
          """, image.key().toString(), image.uploaderId(), image.questionSetId(), image.mimeType(), image.size(),
          image.width(), image.height(), timestamp(image.createdAt()), timestamp(image.expiresAt()),
          timestamp(image.expiresAt()));
      return true;
    });
  }

  @Override
  public boolean finishUpload(UUID key, Instant now, BooleanSupplier write) {
    return independent.execute(transaction -> {
      if (!lock(key, "status = 'UPLOADING' AND question_set_id IS NOT NULL AND expires_at > ?", timestamp(now))
          || !uploadStillAuthorized(key)) {
        return false;
      }
      if (!write.getAsBoolean()) {
        jdbc.update("DELETE FROM question_images WHERE image_key = ?", key.toString());
        return false;
      }
      if (!uploadStillAuthorized(key)) return false;
      jdbc.update("UPDATE question_images SET status = 'AVAILABLE' WHERE image_key = ?", key.toString());
      return true;
    });
  }

  @Override
  public List<UUID> cleanupCandidates(Instant now, int limit) {
    return jdbc.query("""
        SELECT i.image_key FROM question_images i
        WHERE i.cleanup_after <= ? AND
        """ + UNREFERENCED + " ORDER BY i.cleanup_after, i.image_key LIMIT ?",
        (row, index) -> UUID.fromString(row.getString(1)), timestamp(now), limit);
  }

  @Override
  public boolean claimDeletion(UUID key, Instant now) {
    return independent.execute(transaction -> {
      if (!lock(key, "cleanup_after <= ?", timestamp(now)) || !unreferenced(key)) {
        return false;
      }
      jdbc.update("UPDATE question_images SET status = 'DELETE_PENDING', cleanup_after = ? WHERE image_key = ?",
          timestamp(now.plusSeconds(60)), key.toString());
      return true;
    });
  }

  @Override
  public void finishDeletion(UUID key, Runnable delete) {
    independent.executeWithoutResult(transaction -> {
      if (lock(key, "status = 'DELETE_PENDING'") && unreferenced(key)) {
        delete.run();
        jdbc.update("DELETE FROM question_images WHERE image_key = ?", key.toString());
      }
    });
  }

  // All reference writers acquire the set, then image, then write the question. Cleanup only
  // takes the image lock and uses READ COMMITTED reads of the two FK indexes.
  @Override
  public boolean attachToQuestion(UUID key, long uploaderId, long setId, long questionId, Instant now) {
    return attachment.execute(transaction -> ownsSet(uploaderId, setId, true)
        && lock(key, "status = 'AVAILABLE' AND uploader_id = ? AND question_set_id = ? AND expires_at > ?",
            uploaderId, setId, timestamp(now))
        && jdbc.update("UPDATE questions SET image_key = ? WHERE id = ? AND question_set_id = ?",
            key.toString(), questionId, setId) == 1);
  }

  private boolean uploadStillAuthorized(UUID key) {
    return !jdbc.queryForList("""
        SELECT 1 FROM question_images i
        JOIN question_sets s ON s.id = i.question_set_id AND s.host_id = i.uploader_id
        JOIN users u ON u.id = i.uploader_id AND u.role = 'PROFESSOR'
        WHERE i.image_key = ?
        """, Integer.class, key.toString()).isEmpty();
  }

  private boolean ownsSet(long userId, long setId, boolean lock) {
    return !jdbc.queryForList("""
        SELECT id FROM question_sets WHERE id = ? AND host_id = ?
        AND EXISTS (SELECT 1 FROM users WHERE id = ? AND role = 'PROFESSOR')
        """ + (lock ? " FOR UPDATE" : ""), Long.class, setId, userId, userId).isEmpty();
  }

  private boolean unreferenced(UUID key) {
    return !jdbc.queryForList("SELECT i.image_key FROM question_images i WHERE i.image_key = ? AND " + UNREFERENCED,
        String.class, key.toString()).isEmpty();
  }

  private boolean lock(UUID key, String condition, Object... arguments) {
    Object[] parameters = new Object[arguments.length + 1];
    parameters[0] = key.toString();
    System.arraycopy(arguments, 0, parameters, 1, arguments.length);
    return !jdbc.queryForList("SELECT image_key FROM question_images WHERE image_key = ? AND "
        + condition + " FOR UPDATE", String.class, parameters).isEmpty();
  }

  private static LocalDateTime timestamp(Instant time) {
    return LocalDateTime.ofInstant(time, ZoneOffset.UTC);
  }
}
