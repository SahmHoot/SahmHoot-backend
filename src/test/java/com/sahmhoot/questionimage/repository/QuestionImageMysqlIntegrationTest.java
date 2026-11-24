package com.sahmhoot.questionimage.repository;

import static org.assertj.core.api.Assertions.*;

import com.sahmhoot.questionimage.service.QuestionImageLifecycleRepository.Reservation;
import com.sahmhoot.questionimage.storage.ImageStorageException;
import com.sahmhoot.questionimage.storage.LocalImageStorage;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

// This opt-in test owns its empty disposable database from V2 through V3.
@EnabledIfEnvironmentVariable(named = "IMAGE_MIGRATION_DB_URL", matches = ".+")
class QuestionImageMysqlIntegrationTest {
  private static final Instant CREATED = Instant.parse("2026-11-24T00:00:00Z");
  private static final Instant EXPIRES = CREATED.plusSeconds(86_400);
  private static final AtomicLong IDS = new AtomicLong(100);
  private static JdbcTemplate jdbc;
  private static JdbcQuestionImageLifecycleRepository repository;
  private static TransactionTemplate transactions;

  @TempDir Path directory;

  @BeforeAll
  static void upgradeV2WithExistingQuestionsAndRunHistory() {
    var source = new DriverManagerDataSource(System.getenv("IMAGE_MIGRATION_DB_URL"),
        System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"));
    jdbc = new JdbcTemplate(source);
    var manager = new DataSourceTransactionManager(source);
    repository = new JdbcQuestionImageLifecycleRepository(jdbc, manager);
    transactions = new TransactionTemplate(manager);
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
        """, Integer.class)).as("Use only an empty disposable database").isZero();
    Flyway.configure().dataSource(source).target("2").load().migrate();
    assertThat(createQuestionSet()).isEqualTo(101);
    createRun(101);
    Flyway.configure().dataSource(source).load().migrate();
  }

  @Test
  void preservesPreviousDataWithNullReferencesAndRejectsInvalidKeys() {
    assertThat(jdbc.queryForMap("SELECT content, image_key FROM questions WHERE id = 101"))
        .containsEntry("content", "Question").containsEntry("image_key", null);
    assertThat(jdbc.queryForMap("SELECT content, image_key FROM run_questions WHERE id = 101"))
        .containsEntry("content", "Snapshot").containsEntry("image_key", null);
    for (String table : new String[] {"questions", "run_questions"}) {
      assertThatThrownBy(() -> jdbc.update("UPDATE " + table + " SET image_key = ? WHERE id = 101",
          UUID.randomUUID().toString())).isInstanceOf(DataIntegrityViolationException.class);
    }
  }

  @Test
  void verifiesProfessorSetAndUploaderBeforeAttaching() {
    long set = createQuestionSet();
    long other = createQuestionSet();
    UUID key = available(set);
    assertThat(repository.reserve(reservation(UUID.randomUUID(), other, set))).isFalse();
    jdbc.update("UPDATE users SET role = 'STUDENT' WHERE id = ?", other);
    assertThat(repository.reserve(reservation(UUID.randomUUID(), other, other))).isFalse();
    assertThat(repository.attachToQuestion(key, other, other, other, CREATED)).isFalse();
    assertThat(repository.attachToQuestion(key, set, set, other, CREATED)).isFalse();
    assertThat(repository.attachToQuestion(key, set, set, set, CREATED)).isTrue();
    assertThat(repository.cleanupCandidates(EXPIRES, 100)).doesNotContain(key);
    assertThat(repository.claimDeletion(key, EXPIRES)).isFalse();
    assertThatThrownBy(() -> jdbc.update("DELETE FROM question_images WHERE image_key = ?", key.toString()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void preservesMetadataOnSetDeletionAndProtectsAnExecutionReference() {
    long set = createQuestionSet();
    UUID key = available(set);
    jdbc.update("DELETE FROM questions WHERE id = ?", set);
    jdbc.update("DELETE FROM question_sets WHERE id = ?", set);
    assertThat(jdbc.queryForMap("SELECT question_set_id FROM question_images WHERE image_key = ?", key.toString()))
        .containsEntry("question_set_id", null);
    assertThat(repository.cleanupCandidates(EXPIRES, 100)).contains(key);
    long historicSet = createQuestionSet();
    createRun(historicSet);
    UUID snapshotKey = available(historicSet);
    assertThat(repository.attachToQuestion(snapshotKey, historicSet, historicSet, historicSet, CREATED)).isTrue();
    jdbc.update("UPDATE run_questions SET image_key = ? WHERE id = ?", snapshotKey.toString(), historicSet);
    jdbc.update("DELETE FROM questions WHERE id = ?", historicSet);
    assertThat(repository.claimDeletion(snapshotKey, EXPIRES)).isFalse();
    assertThat(repository.cleanupCandidates(EXPIRES, 100)).doesNotContain(snapshotKey);
    assertThatThrownBy(() -> jdbc.update("DELETE FROM question_images WHERE image_key = ?", snapshotKey.toString()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void cleansAnUploadThatWroteTheFileButRolledBackAndRetriesFailedDeletion() {
    long set = createQuestionSet();
    UUID key = UUID.randomUUID();
    var storage = new LocalImageStorage(directory);
    assertThat(repository.reserve(reservation(key, set, set))).isTrue();
    assertThatThrownBy(() -> repository.finishUpload(key, CREATED, () -> {
      storage.save(key, "image/png", new byte[] {1});
      jdbc.update("UPDATE question_images SET size_bytes = 0 WHERE image_key = ?", key.toString());
      return true;
    })).isInstanceOf(DataAccessException.class).hasRootCauseInstanceOf(SQLException.class)
        .hasStackTraceContaining("ck_question_images_size");
    assertThat(status(key)).isEqualTo("UPLOADING");
    assertThat(storage.read(key)).containsExactly((byte) 1);
    assertThat(repository.claimDeletion(key, EXPIRES.minusNanos(1_000))).isFalse();
    assertThat(repository.cleanupCandidates(EXPIRES, 100)).contains(key);
    assertThat(repository.claimDeletion(key, EXPIRES)).isTrue();
    assertThatThrownBy(() -> repository.finishDeletion(key, () -> {
      throw new ImageStorageException(ImageStorageException.Reason.UNAVAILABLE);
    })).isInstanceOf(ImageStorageException.class);
    assertThat(status(key)).isEqualTo("DELETE_PENDING");
    assertThat(repository.claimDeletion(key, EXPIRES.plusSeconds(59))).isFalse();
    assertThat(repository.claimDeletion(key, EXPIRES.plusSeconds(60))).isTrue();
    assertThatThrownBy(() -> repository.finishDeletion(key, () -> {
      storage.delete(key);
      jdbc.update("UPDATE question_images SET size_bytes = 0 WHERE image_key = ?", key.toString());
    })).isInstanceOf(DataAccessException.class).hasRootCauseInstanceOf(SQLException.class)
        .hasStackTraceContaining("ck_question_images_size");
    assertThat(status(key)).isEqualTo("DELETE_PENDING");
    var restarted = new JdbcQuestionImageLifecycleRepository(jdbc,
        new DataSourceTransactionManager(jdbc.getDataSource()));
    assertThat(restarted.claimDeletion(key, EXPIRES.plusSeconds(120))).isTrue();
    restarted.finishDeletion(key, () -> storage.delete(key));
    restarted.finishDeletion(key, () -> fail("Removal cannot run again after metadata is deleted"));
    assertThatThrownBy(() -> storage.read(key)).isInstanceOf(ImageStorageException.class);
  }

  @Test
  void serializesUncommittedAttachmentAgainstCleanupAndRejectsAnAlreadyClaimedImage() throws Exception {
    long set = createQuestionSet();
    UUID key = available(set);
    var attached = new CountDownLatch(1);
    var commit = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var writer = executor.submit(() -> transactions.executeWithoutResult(tx -> {
        assertThat(repository.attachToQuestion(key, set, set, set, CREATED)).isTrue();
        attached.countDown();
        await(commit);
      }));
      try {
        await(attached);
        var cleaner = executor.submit(() -> repository.claimDeletion(key, EXPIRES));
        assertThatThrownBy(() -> cleaner.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        commit.countDown();
        writer.get(10, TimeUnit.SECONDS);
        assertThat(cleaner.get(10, TimeUnit.SECONDS)).isFalse();
      } finally {
        commit.countDown();
      }
    }
    UUID claimed = available(set);
    assertThat(repository.claimDeletion(claimed, EXPIRES)).isTrue();
    assertThat(repository.attachToQuestion(claimed, set, set, set, CREATED)).isFalse();
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  private String status(UUID key) {
    return jdbc.queryForObject("SELECT status FROM question_images WHERE image_key = ?", String.class, key.toString());
  }

  private UUID available(long set) {
    UUID key = UUID.randomUUID();
    assertThat(repository.reserve(reservation(key, set, set))).isTrue();
    assertThat(repository.finishUpload(key, CREATED, () -> true)).isTrue();
    return key;
  }

  private Reservation reservation(UUID key, long uploader, long set) {
    return new Reservation(key, uploader, set, "image/png", 1, 1, 1, CREATED, EXPIRES);
  }

  private static long createQuestionSet() {
    long id = IDS.incrementAndGet();
    jdbc.update("""
        INSERT INTO users (id,email,password_hash,name,role,created_at,updated_at)
        VALUES (?,?,'test-only','Owner','PROFESSOR',UTC_TIMESTAMP(),UTC_TIMESTAMP())
        """, id, "owner" + id + "@test.invalid");
    jdbc.update("""
        INSERT INTO question_sets (id,host_id,title,visibility,created_at,updated_at)
        VALUES (?,?,'Set','PRIVATE',UTC_TIMESTAMP(),UTC_TIMESTAMP())
        """, id, id);
    jdbc.update("""
        INSERT INTO questions (id,question_set_id,type,content,time_limit_seconds,order_no,created_at,updated_at)
        VALUES (?,?,'TRUE_FALSE','Question',30,1,UTC_TIMESTAMP(),UTC_TIMESTAMP())
        """, id, id);
    return id;
  }

  private static void createRun(long id) {
    jdbc.update("""
        INSERT INTO rooms (id,host_id,title,code,status,created_at)
        VALUES (?,?,'Room',?,'CLOSED',UTC_TIMESTAMP())
        """, id, id, String.format("%06d", id));
    jdbc.update("INSERT INTO quiz_runs (id,room_id,question_set_id,status) VALUES (?,?,?,'FINISHED')", id, id, id);
    jdbc.update("""
        INSERT INTO run_questions (id,quiz_run_id,type,content,time_limit_seconds,order_no,status)
        VALUES (?,?,'TRUE_FALSE','Snapshot',30,1,'CLOSED')
        """, id, id);
  }
}
