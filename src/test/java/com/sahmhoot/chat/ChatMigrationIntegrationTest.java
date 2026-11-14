package com.sahmhoot.chat;

import static org.assertj.core.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in: requires an EMPTY, disposable MySQL 8.4 schema, never the shared test DB. */
@EnabledIfEnvironmentVariable(named = "CHAT_MIGRATION_DB_URL", matches = ".+")
class ChatMigrationIntegrationTest {
  private Connection connection;

  private static String username() {
    return System.getenv().getOrDefault("CHAT_MIGRATION_DB_USERNAME", System.getenv("DB_USERNAME"));
  }

  private static String password() {
    return System.getenv().getOrDefault("CHAT_MIGRATION_DB_PASSWORD", System.getenv("DB_PASSWORD"));
  }

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection(System.getenv("CHAT_MIGRATION_DB_URL"),
        username(), password());
  }

  @BeforeAll static void upgradeExistingV1WithoutChangingData() throws Exception {
    try (var c = connect(); var s = c.createStatement()) {
      try (var r = s.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()")) {
        r.next();
        assertThat(r.getInt(1)).as("Use an empty disposable schema; existing schemas are never migrated by this test")
            .isZero();
      }
    }
    var configuration = Flyway.configure().dataSource(System.getenv("CHAT_MIGRATION_DB_URL"),
        username(), password());
    configuration.target("1").load().migrate();
    try (var c = connect(); var s = c.createStatement()) {
      s.executeUpdate("""
          INSERT INTO users(id,email,password_hash,name,role,created_at,updated_at)
          VALUES (80000,'preserved@test.invalid','test-only','Preserved','PROFESSOR',UTC_TIMESTAMP(),UTC_TIMESTAMP())
          """);
      s.executeUpdate("""
          INSERT INTO rooms(id,host_id,title,code,status,created_at)
          VALUES (80000,80000,'Legacy room','oldv1a','OPEN',UTC_TIMESTAMP())
          """);
      s.executeUpdate("""
          INSERT INTO room_participants(id,room_id,user_id,nickname,joined_at)
          VALUES (80000,80000,80000,'Legacy nickname',UTC_TIMESTAMP())
          """);
    }
    var flyway = configuration.target("latest").load();
    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
    flyway.validate();
    assertThat(flyway.migrate().migrationsExecuted).isZero();
    try (var c = connect(); var s = c.createStatement()) {
      try (var r = s.executeQuery("SELECT name FROM users WHERE id = 80000")) {
        assertThat(r.next()).isTrue();
        assertThat(r.getString(1)).isEqualTo("Preserved");
      }
      try (var r = s.executeQuery("""
          SELECT r.title,p.nickname,p.user_id FROM rooms r JOIN room_participants p ON p.room_id=r.id
          WHERE r.id=80000 AND p.id=80000
          """)) {
        assertThat(r.next()).isTrue();
        assertThat(r.getString(1)).isEqualTo("Legacy room");
        assertThat(r.getString(2)).isEqualTo("Legacy nickname");
        assertThat(r.getLong(3)).isEqualTo(80000L);
      }
      s.executeUpdate("DELETE FROM room_participants WHERE id=80000");
      s.executeUpdate("DELETE FROM rooms WHERE id=80000");
      s.executeUpdate("DELETE FROM users WHERE id = 80000"); // Only this test's fixture.
      // DDL runs before transactional fixtures: the participant index cannot be dropped with these FKs.
      for (String index : List.of("uq_room_participants_chat_room_id")) {
        assertThatThrownBy(() -> s.executeUpdate("ALTER TABLE room_participants DROP INDEX " + index))
            .isInstanceOfSatisfying(SQLException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(1553));
      }
    }
  }

  @BeforeEach void setup() throws Exception {
    connection = connect();
    connection.setAutoCommit(false);
    sql("""
        INSERT INTO users(id,email,password_hash,name,role,created_at,updated_at) VALUES
        (90001,'professor@test.invalid','test-only','Professor','PROFESSOR',UTC_TIMESTAMP(),UTC_TIMESTAMP()),
        (90002,'student@test.invalid','test-only','Student','STUDENT',UTC_TIMESTAMP(),UTC_TIMESTAMP())
        """);
    sql("""
        INSERT INTO rooms(id,host_id,title,code,status,created_at) VALUES
        (90001,90001,'Room A','chat01','OPEN',UTC_TIMESTAMP()),
        (90002,90001,'Room B','chat02','OPEN',UTC_TIMESTAMP())
        """);
    sql("""
        INSERT INTO room_participants(id,room_id,user_id,nickname,joined_at)
        VALUES (90001,90001,90002,'Anonymous',UTC_TIMESTAMP())
        """);
  }

  @AfterEach void rollback() throws Exception {
    if (connection != null) {
      connection.rollback();
      connection.close();
    }
  }

  @Test void professorHostAndStudentCanBothStoreMessagesDuringClass() throws Exception {
    sql("UPDATE rooms SET status='PLAYING' WHERE id=90001");
    professorMessage(1, 90001, "Welcome", null);
    message(2, 90001, "Student message", null);
    assertThat(count("chat_messages")).isEqualTo(2);
    try (var st = connection.createStatement(); var r = st.executeQuery("""
        SELECT p.user_id FROM chat_messages m JOIN room_participants p
        ON p.room_id=m.room_id AND p.id=m.sender_participant_id ORDER BY m.id
        """)) {
      assertThat(r.next()).isTrue();
      assertThat(r.getLong(1)).isEqualTo(90001L);
      assertThat(r.next()).isTrue();
      assertThat(r.getLong(1)).isEqualTo(90002L);
      assertThat(r.next()).isFalse();
    }
  }

  @Test void displayAndEmailUseParticipantsIncludingNonOwnerProfessors() throws Exception {
    message(1, 90001, "Student question", null);
    professorMessage(2, 90001, "Owner reply", 1L);
    sql("""
        INSERT INTO users(id,email,password_hash,name,role,created_at,updated_at)
        VALUES (90003,'other-professor@test.invalid','test-only','Account name','PROFESSOR',
        UTC_TIMESTAMP(),UTC_TIMESTAMP())
        """);
    sql("""
        INSERT INTO room_participants(id,room_id,user_id,nickname,joined_at)
        VALUES (90003,90001,90003,'Guest nickname',UTC_TIMESTAMP())
        """);
    sql("""
        INSERT INTO chat_messages(id,room_id,sender_participant_id,content,created_at)
        VALUES (3,90001,90003,'Guest professor','2030-01-01 00:00:00')
        """);
    sql("UPDATE users SET name='Changed account name' WHERE id IN (90001,90002,90003)");
    try (var st = connection.createStatement(); var r = st.executeQuery("""
        SELECT m.id,p.nickname,(p.user_id=room.host_id) AS is_owner,u.email,
        m.parent_id,parent.content AS parent_content
        FROM chat_messages m JOIN rooms room ON room.id=m.room_id
        JOIN room_participants p ON p.room_id=m.room_id AND p.id=m.sender_participant_id
        JOIN users u ON u.id=p.user_id
        LEFT JOIN chat_messages parent ON parent.room_id=m.room_id AND parent.id=m.parent_id
        ORDER BY m.id
        """)) {
      assertThat(r.next()).isTrue();
      assertThat(r.getString("nickname")).isEqualTo("Anonymous");
      assertThat(r.getBoolean("is_owner")).isFalse();
      assertThat(r.getString("email")).isEqualTo("student@test.invalid");
      assertThat(r.getObject("parent_id")).isNull();
      assertThat(r.next()).isTrue();
      assertThat(r.getString("nickname")).isEqualTo("Owner nickname");
      assertThat(r.getBoolean("is_owner")).isTrue();
      assertThat(r.getString("email")).isEqualTo("professor@test.invalid");
      assertThat(r.getLong("parent_id")).isEqualTo(1L);
      assertThat(r.getString("parent_content")).isEqualTo("Student question");
      assertThat(r.next()).isTrue();
      assertThat(r.getString("nickname")).isEqualTo("Guest nickname");
      assertThat(r.getBoolean("is_owner")).isFalse();
      assertThat(r.getString("email")).isEqualTo("other-professor@test.invalid");
      assertThat(r.next()).isFalse();
    }
  }

  @Test void nonParticipantsCannotCreateMessagesAndParticipantIsRequired() throws Exception {
    // A valid user account alone is insufficient; it has no participant row yet.
    rejected(() -> sql("""
        INSERT INTO chat_messages(room_id,sender_participant_id,content,created_at)
        VALUES (90001,90002,'Not a participant',UTC_TIMESTAMP(6))
        """));
    rejected(() -> sql("""
        INSERT INTO chat_messages(room_id,sender_participant_id,content,created_at)
        VALUES (90001,NULL,'Missing participant',UTC_TIMESTAMP(6))
        """));
    message(1, 90001, "Valid", null);
    rejected(() -> sql("UPDATE chat_messages SET sender_participant_id=NULL WHERE id=1"));
    sql("""
        INSERT INTO room_participants(id,room_id,user_id,nickname,joined_at)
        VALUES (90002,90002,90002,'Other room',UTC_TIMESTAMP())
        """);
    rejected(() -> sql("UPDATE chat_messages SET sender_participant_id=90002 WHERE id=1"));
    assertThat(count("chat_messages")).isEqualTo(1);
  }

  @Test void messagesDoNotStoreSenderDisplaySnapshots() throws Exception {
    try (var s = connection.createStatement(); var r = s.executeQuery("""
        SELECT column_name FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='chat_messages' ORDER BY ordinal_position
        """)) {
      var columns = new ArrayList<String>();
      while (r.next()) columns.add(r.getString(1));
      assertThat(columns).containsExactly("id", "room_id", "sender_participant_id",
          "content", "parent_id", "created_at", "deleted_at");
    }
    try (var s = connection.createStatement(); var r = s.executeQuery("""
        SELECT constraint_name FROM information_schema.table_constraints
        WHERE constraint_schema=DATABASE() AND table_name='chat_messages' AND constraint_type='CHECK'
        """)) {
      var constraints = new ArrayList<String>();
      while (r.next()) constraints.add(r.getString(1));
      assertThat(constraints).containsExactly("ck_chat_message_content");
    }
  }

  @Test void participationMustMatchTheMessageRoom() throws Exception {
    message(1, 90001, "Student message", null);
    professorMessage(2, 90001, "Professor message", null);
    rejected(() -> sql("UPDATE chat_messages SET room_id=90002 WHERE id=1"));
    rejected(() -> sql("UPDATE chat_messages SET sender_participant_id=99999 WHERE id=1"));
    try (var s = connection.createStatement(); var r = s.executeQuery("""
        SELECT sender_participant_id FROM chat_messages ORDER BY id
        """)) {
      assertThat(r.next()).isTrue();
      assertThat(r.getObject(1, Long.class)).isEqualTo(90001L);
      assertThat(r.next()).isTrue();
      assertThat(r.getObject(1, Long.class)).isEqualTo(190001L);
    }
  }

  @Test void participantForeignKeysUseUniqueKeysThatV1IndexesCannotReplace() throws Exception {
    assertThat(indexColumns("room_participants", "PRIMARY")).containsExactly("id");
    assertThat(indexColumns("room_participants", "room_participants_index_2"))
        .containsExactly("room_id", "user_id");
    assertThat(indexColumns("room_participants", "room_participants_index_3"))
        .containsExactly("room_id", "nickname");
    assertThat(indexColumns("room_participants", "uq_room_participants_chat_room_id"))
        .containsExactly("room_id", "id");
    assertThat(indexColumns("room_participants", "uq_room_participants_chat_sender"))
        .isEmpty();
    try (var s = connection.createStatement(); var r = s.executeQuery("""
        SELECT constraint_name,unique_constraint_name FROM information_schema.referential_constraints
        WHERE constraint_schema=DATABASE()
        AND constraint_name IN ('fk_chat_restriction_participant','fk_chat_message_participant')
        ORDER BY constraint_name
        """)) {
      assertThat(r.next()).isTrue();
      assertThat(r.getString(1)).isEqualTo("fk_chat_message_participant");
      assertThat(r.getString(2)).isEqualTo("uq_room_participants_chat_room_id");
      assertThat(r.next()).isTrue();
      assertThat(r.getString(1)).isEqualTo("fk_chat_restriction_participant");
      assertThat(r.getString(2)).isEqualTo("uq_room_participants_chat_room_id");
      assertThat(r.next()).isFalse();
    }
  }

  @Test void repliesAndPinsCannotCrossRooms() throws Exception {
    message(1, 90001, "Parent", null);
    professorMessage(2, 90001, "Reply", 1L);
    rejected(() -> professorMessage(3, 90002, "Wrong room", 1L));
    sql("INSERT INTO chat_pins VALUES (90001,1,90001,UTC_TIMESTAMP())");
    sql("INSERT INTO chat_pins VALUES (90001,2,90001,UTC_TIMESTAMP())");
    assertThat(count("chat_pins")).isEqualTo(2);
    rejected(() -> sql("INSERT INTO chat_pins VALUES (90002,1,90001,UTC_TIMESTAMP())"));
    rejected(() -> sql("INSERT INTO chat_pins VALUES (90001,1,90001,UTC_TIMESTAMP())"));
    rejected(() -> sql("UPDATE chat_pins SET pinned_by_user_id=99999 WHERE message_id=1"));
    rejected(() -> professorMessage(4, 90001, "Missing parent", 999L));
  }

  @Test void roomCanKeepManyPinsAndUnpinMessagesIndependently() throws Exception {
    // CH-08: pins are independent of the recent-50 history page size.
    for (int i = 1; i <= 60; i++) message(i, 90001, "Pinned message " + i, null);
    assertThat(sql("""
        INSERT INTO chat_pins(room_id,message_id,pinned_by_user_id,pinned_at)
        SELECT room_id,id,90001,'2030-01-01 00:00:00' FROM chat_messages WHERE room_id=90001
        """)).isEqualTo(60);
    assertThat(count("chat_pins")).isEqualTo(60);
    rejected(() -> sql("INSERT INTO chat_pins VALUES (90001,1,90001,UTC_TIMESTAMP())"));
    assertThat(sql("DELETE FROM chat_pins WHERE room_id=90001 AND message_id=1")).isEqualTo(1);
    assertThat(count("chat_pins")).isEqualTo(59);
    assertThat(count("chat_messages")).isEqualTo(60);
    sql("INSERT INTO chat_pins VALUES (90001,1,90001,'2030-01-02 00:00:00')");
    assertThat(count("chat_pins")).isEqualTo(60);
    try (var s = connection.createStatement(); var r = s.executeQuery("""
        SELECT pinned_at FROM chat_pins WHERE room_id=90001 AND message_id=1
        """)) {
      assertThat(r.next()).isTrue();
      assertThat(r.getObject(1, java.time.LocalDateTime.class))
          .isEqualTo(java.time.LocalDateTime.of(2030, 1, 2, 0, 0));
      assertThat(r.next()).isFalse();
    }
  }

  @Test void reconnectingRestoresLatestFiftyAndPinsOutsideThatHistoryPage() throws Exception {
    try {
      sql("""
          INSERT INTO room_participants(id,room_id,user_id,nickname,joined_at)
          VALUES (90002,90002,90002,'Anonymous B',UTC_TIMESTAMP())
          """);
      for (int i = 1; i <= 60; i++) message(i, 90001, "Message " + i, null);
      message(61, 90002, "Other room", null);
      sql("UPDATE chat_messages SET deleted_at='2030-01-01 12:00:00' WHERE id=50");
      sql("""
          INSERT INTO chat_pins(room_id,message_id,pinned_by_user_id,pinned_at)
          SELECT room_id,id,90001,UTC_TIMESTAMP(6) FROM chat_messages WHERE deleted_at IS NULL
          """);
      connection.commit(); // A new connection must read committed state, as after reconnecting.
      try (var restored = connect()) {
        restored.setAutoCommit(false);
        var recentIds = new ArrayList<Long>();
        try (var s = restored.prepareStatement("""
            SELECT id,CASE WHEN deleted_at IS NULL THEN content ELSE NULL END AS content,deleted_at
            FROM chat_messages WHERE room_id=? AND deleted_at IS NULL ORDER BY created_at DESC,id DESC LIMIT 50
            """)) {
          s.setLong(1, 90001L);
          try (var r = s.executeQuery()) {
            while (r.next()) {
              recentIds.add(r.getLong("id"));
            }
          }
        }
        assertThat(recentIds).hasSize(50).startsWith(60L, 59L).endsWith(10L).doesNotContain(50L, 61L);
        assertThat(count(restored, "chat_messages")).isEqualTo(61);
        var pinnedIds = new ArrayList<Long>();
        try (var s = restored.prepareStatement("""
            SELECT m.id FROM chat_pins p JOIN chat_messages m
            ON m.room_id=p.room_id AND m.id=p.message_id WHERE p.room_id=? ORDER BY m.id
            """)) {
          s.setLong(1, 90001L);
          try (var r = s.executeQuery()) { while (r.next()) pinnedIds.add(r.getLong(1)); }
        }
        assertThat(pinnedIds).hasSize(59).startsWith(1L, 2L).endsWith(60L).doesNotContain(50L, 61L);
        restored.rollback();
      }
    } finally {
      // Remove only this test's committed fixtures in the opt-in disposable schema.
      sql("DELETE FROM chat_messages WHERE room_id IN (90001,90002)");
      sql("DELETE FROM room_participants WHERE room_id IN (90001,90002)");
      sql("DELETE FROM rooms WHERE id IN (90001,90002)");
      sql("DELETE FROM users WHERE id IN (90001,90002)");
      connection.commit();
    }
  }

  @Test void databaseLeavesReplyAuthorizationToTheFutureService() throws Exception {
    message(1, 90001, "Student question", null);
    professorMessage(2, 90001, "Professor announcement", null);
    // These rows are structurally valid; the future service must reject unauthorized replies.
    message(3, 90001, "Student reply for persistence contract", 1L);
    professorMessage(4, 90001, "Professor answer", 1L);
    professorMessage(5, 90001, "Reply to professor for persistence contract", 2L);
    professorMessage(6, 90001, "Nested reply for persistence contract", 4L);
    assertThat(count("chat_messages")).isEqualTo(6);
    try (var s = connection.createStatement(); var r = s.executeQuery("""
        SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
        AND table_name='chat_messages' AND extra LIKE '%GENERATED%'
        """)) {
      r.next();
      assertThat(r.getInt(1)).isZero();
    }
  }

  @Test void restrictionsAreUniqueAndRequireParticipationInThatRoom() throws Exception {
    sql("INSERT INTO chat_restrictions VALUES (90001,90001,90001,UTC_TIMESTAMP())");
    rejected(() -> sql("INSERT INTO chat_restrictions VALUES (90001,90001,90001,UTC_TIMESTAMP())"));
    rejected(() -> sql("INSERT INTO chat_restrictions VALUES (90002,90001,90001,UTC_TIMESTAMP())"));
    rejected(() -> sql("INSERT INTO chat_restrictions VALUES (90001,99999,90001,UTC_TIMESTAMP())"));
    rejected(() -> sql("UPDATE chat_restrictions SET restricted_by_user_id=99999 WHERE room_id=90001"));
    assertThat(sql("DELETE FROM chat_restrictions WHERE room_id=90001 AND participant_id=90001")).isEqualTo(1);
    assertThat(count("chat_restrictions")).isZero();
    sql("INSERT INTO chat_restrictions VALUES (90001,90001,90001,UTC_TIMESTAMP())");
  }

  @Test void restrictingAndReleasingOneParticipantDoesNotAffectOtherRoomsOrParticipants() throws Exception {
    // CH-04 persistence contract; professor authorization and send blocking belong to the service.
    sql("""
        INSERT INTO users(id,email,password_hash,name,role,created_at,updated_at)
        VALUES (90003,'another-student@test.invalid','test-only','Another student','STUDENT',
        UTC_TIMESTAMP(),UTC_TIMESTAMP())
        """);
    sql("""
        INSERT INTO room_participants(id,room_id,user_id,nickname,joined_at) VALUES
        (90002,90002,90002,'Anonymous B',UTC_TIMESTAMP()),
        (90003,90001,90003,'Another anonymous',UTC_TIMESTAMP())
        """);
    message(1, 90001, "Existing history", null);
    sql("""
        INSERT INTO chat_restrictions(room_id,participant_id,restricted_by_user_id,restricted_at) VALUES
        (90001,90001,90001,UTC_TIMESTAMP()),
        (90002,90002,90001,UTC_TIMESTAMP()),
        (90001,90003,90001,UTC_TIMESTAMP())
        """);
    assertThat(sql("DELETE FROM chat_restrictions WHERE room_id=90001 AND participant_id=90001")).isEqualTo(1);
    assertThat(sql("DELETE FROM chat_restrictions WHERE room_id=90001 AND participant_id=90001")).isZero();
    try (var s = connection.createStatement(); var r = s.executeQuery("""
        SELECT room_id,participant_id,restricted_by_user_id FROM chat_restrictions ORDER BY room_id,participant_id
        """)) {
      assertThat(r.next()).isTrue();
      assertThat(r.getLong(1)).isEqualTo(90001L);
      assertThat(r.getLong(2)).isEqualTo(90003L);
      assertThat(r.getLong(3)).isEqualTo(90001L);
      assertThat(r.next()).isTrue();
      assertThat(r.getLong(1)).isEqualTo(90002L);
      assertThat(r.getLong(2)).isEqualTo(90002L);
      assertThat(r.getLong(3)).isEqualTo(90001L);
      assertThat(r.next()).isFalse();
    }
    sql("INSERT INTO chat_restrictions VALUES (90001,90001,90001,UTC_TIMESTAMP())");
    assertThat(count("chat_restrictions")).isEqualTo(3);
    assertThat(count("chat_messages")).isEqualTo(1);
    assertThat(count("room_participants")).isEqualTo(3);
  }

  @Test void messagesEnforceCharacterLimitsIncludingMultibyteText() throws Exception {
    message(1, 90001, "가😀".repeat(150), null);
    message(2, 90001, "a", null);
    rejected(() -> message(3, 90001, "", null));
    rejected(() -> message(4, 90001, "가".repeat(301), null));
    rejected(() -> message(5, 90001, null, null));
  }

  @Test void committedRestrictionAndReleaseAreVisibleAcrossConnectionsWithoutHistory() throws Exception {
    try {
      sql("INSERT INTO chat_restrictions VALUES (90001,90001,90001,'2030-01-01 00:00:00')");
      connection.commit();
      try (var tabA = connect(); var tabB = connect()) {
        assertThat(count(tabA, "chat_restrictions")).isEqualTo(1);
        assertThat(count(tabB, "chat_restrictions")).isEqualTo(1);
        sql("DELETE FROM chat_restrictions WHERE room_id=90001 AND participant_id=90001");
        connection.commit();
        assertThat(count(tabA, "chat_restrictions")).isZero();
        assertThat(count(tabB, "chat_restrictions")).isZero();
        sql("INSERT INTO chat_restrictions VALUES (90001,90001,90001,'2030-01-02 00:00:00')");
        connection.commit();
        assertThat(count(tabA, "chat_restrictions")).isEqualTo(1);
        assertThat(count(tabB, "chat_restrictions")).isEqualTo(1);
      }
    } finally {
      // Only fixtures from this opt-in disposable schema test are committed/deleted.
      sql("DELETE FROM chat_restrictions WHERE room_id=90001 AND participant_id=90001");
      sql("DELETE FROM room_participants WHERE room_id IN (90001,90002)");
      sql("DELETE FROM rooms WHERE id IN (90001,90002)");
      sql("DELETE FROM users WHERE id IN (90001,90002)");
      connection.commit();
    }
  }

  @Test void foreignKeysPreserveRoomParticipantAndUserRecords() throws Exception {
    message(1, 90001, "Keep history", null);
    rejected(() -> sql("DELETE FROM users WHERE id=90002"));
    rejected(() -> sql("DELETE FROM rooms WHERE id=90001"));
    sql("INSERT INTO chat_restrictions VALUES (90001,90001,90001,UTC_TIMESTAMP())");
    rejected(() -> sql("DELETE FROM room_participants WHERE id=90001"));
    rejected(() -> sql("UPDATE chat_messages SET sender_participant_id=99999 WHERE id=1"));
    rejected(() -> sql("UPDATE chat_messages SET room_id=99999 WHERE id=1"));
  }

  @Test void latestFiftyHaveStableOrderAndAnAppropriateIndex() throws Exception {
    for (int i = 1; i <= 60; i++) message(i, 90001, "Message " + i, null);
    var ids = new ArrayList<Long>();
    try (var s = connection.createStatement(); var r = s.executeQuery("""
        SELECT id FROM chat_messages WHERE room_id=90001
        ORDER BY created_at DESC,id DESC LIMIT 50
        """)) {
      while (r.next()) ids.add(r.getLong(1));
    }
    assertThat(ids).hasSize(50).startsWith(60L, 59L).endsWith(11L);
    assertThat(indexColumns("chat_messages", "idx_chat_messages_recent"))
        .containsExactly("room_id", "created_at", "id");
    assertThat(indexColumns("rooms", "idx_rooms_chat_retention"))
        .containsExactly("status", "closed_at", "id");
    assertThat(indexColumns("chat_messages", "PRIMARY")).containsExactly("id");
    assertThat(explainKey("SELECT id FROM chat_messages WHERE room_id=90001 "
        + "ORDER BY created_at DESC,id DESC LIMIT 50")).isEqualTo("idx_chat_messages_recent");
    assertThat(explainKey("SELECT id FROM rooms WHERE status='CLOSED' "
        + "AND closed_at <= UTC_TIMESTAMP(6) - INTERVAL 7 DAY")).isEqualTo("idx_rooms_chat_retention");
  }

  @Test void existingPrimaryAndForeignKeyTypesAndDeletePoliciesStayCompatible() throws Exception {
    try (var s = connection.createStatement(); var r = s.executeQuery("""
        SELECT table_name,column_name,column_type FROM information_schema.columns
        WHERE table_schema=DATABASE() AND (
          (table_name='users' AND column_name='id') OR
          (table_name='rooms' AND column_name IN ('id','host_id')) OR
          (table_name='room_participants' AND column_name IN ('id','room_id','user_id')))
        """)) {
      int columns = 0;
      while (r.next()) {
        assertThat(r.getString("column_type")).isEqualTo("bigint");
        columns++;
      }
      assertThat(columns).isEqualTo(6);
    }
    try (var s = connection.createStatement(); var r = s.executeQuery("""
        SELECT delete_rule FROM information_schema.referential_constraints
        WHERE constraint_schema=DATABASE() AND table_name IN ('rooms','room_participants')
        """)) {
      int foreignKeys = 0;
      while (r.next()) {
        assertThat(r.getString(1)).isIn("NO ACTION", "RESTRICT");
        foreignKeys++;
      }
      assertThat(foreignKeys).isEqualTo(3);
    }
  }

  @Test void ordinaryProjectionContainsOnlyAnonymousDisplayData() throws Exception {
    message(1, 90001, "Hello", null);
    try (var s = connection.createStatement(); var r = s.executeQuery("""
        SELECT m.id,m.room_id,p.nickname AS display_name,(p.user_id=room.host_id) AS is_owner,
        CASE WHEN m.deleted_at IS NULL THEN m.content ELSE NULL END AS content,
        m.parent_id,m.created_at,m.deleted_at
        FROM chat_messages m JOIN rooms room ON room.id=m.room_id
        JOIN room_participants p ON p.room_id=m.room_id AND p.id=m.sender_participant_id
        WHERE m.room_id=90001 AND m.deleted_at IS NULL ORDER BY m.created_at DESC,m.id DESC LIMIT 50
        """)) {
      assertThat(r.next()).isTrue();
      assertThat(r.getString("display_name")).isEqualTo("Anonymous");
      assertThat(r.getBoolean("is_owner")).isFalse();
    }
    try (var s = connection.createStatement(); var r = s.executeQuery("""
        SELECT column_name FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name LIKE 'chat_%'
        """)) {
      while (r.next()) assertThat(r.getString(1)).doesNotContain("email", "student_number");
    }
  }

  @Test void authorEmailCanBeResolvedSeparatelyThroughTheSenderForeignKey() throws Exception {
    // CH-03 persistence contract only: professor authorization belongs to the future service/API.
    message(1, 90001, "Student message", null);
    professorMessage(2, 90001, "Professor message", null);
    try (var s = connection.prepareStatement("""
        SELECT m.id,u.email FROM chat_messages m
        JOIN room_participants p ON p.room_id=m.room_id AND p.id=m.sender_participant_id
        JOIN users u ON u.id=p.user_id
        WHERE m.room_id=? ORDER BY m.id
        """)) {
      s.setLong(1, 90001L);
      try (var r = s.executeQuery()) {
        assertThat(r.next()).isTrue();
        assertThat(r.getLong(1)).isEqualTo(1L);
        assertThat(r.getString(2)).isEqualTo("student@test.invalid");
        assertThat(r.next()).isTrue();
        assertThat(r.getLong(1)).isEqualTo(2L);
        assertThat(r.getString(2)).isEqualTo("professor@test.invalid");
        assertThat(r.next()).isFalse();
      }
    }
  }

  @Test void softDeletionPreservesHistoryAndReplyLinksDuringClass() throws Exception {
    sql("UPDATE rooms SET status='PLAYING' WHERE id=90001");
    message(1, 90001, "Original content", null);
    professorMessage(2, 90001, "Reply", 1L);
    sql("INSERT INTO chat_pins VALUES (90001,1,90001,UTC_TIMESTAMP())");
    assertThat(sql("UPDATE chat_messages SET deleted_at='2030-01-01 12:00:00' "
        + "WHERE room_id=90001 AND id=1 AND deleted_at IS NULL")).isEqualTo(1);
    assertThat(sql("UPDATE chat_messages SET deleted_at='2030-01-01 13:00:00' "
        + "WHERE room_id=90001 AND id=1 AND deleted_at IS NULL")).isZero();
    // Future service transaction: soft deletion must explicitly unpin; UPDATE has no cascade.
    assertThat(sql("DELETE FROM chat_pins WHERE room_id=90001 AND message_id=1")).isEqualTo(1);
    try (var s = connection.createStatement(); var r = s.executeQuery("""
        SELECT id,content,parent_id,deleted_at,
        CASE WHEN deleted_at IS NULL THEN content ELSE NULL END AS visible_content
        FROM chat_messages WHERE room_id=90001 ORDER BY created_at,id
        """)) {
      assertThat(r.next()).isTrue();
      assertThat(r.getLong("id")).isEqualTo(1L);
      assertThat(r.getString("content")).isEqualTo("Original content");
      assertThat(r.getObject("deleted_at", java.time.LocalDateTime.class))
          .isEqualTo(java.time.LocalDateTime.of(2030, 1, 1, 12, 0));
      assertThat(r.getString("visible_content")).isNull();
      assertThat(r.next()).isTrue();
      assertThat(r.getLong("id")).isEqualTo(2L);
      assertThat(r.getLong("parent_id")).isEqualTo(1L);
      assertThat(r.getTimestamp("deleted_at")).isNull();
      assertThat(r.getString("visible_content")).isEqualTo("Reply");
      assertThat(r.next()).isFalse();
    }
    assertThat(count("chat_messages")).isEqualTo(2);
    assertThat(count("chat_pins")).isZero();
  }

  @Test void retentionDeletesMessagesPinsAndRestrictionsAtTheInclusiveSevenDayBoundary() throws Exception {
    // Fixed clock: expired, exactly seven days, recent, and inconsistent OPEN room.
    sql("UPDATE rooms SET status='CLOSED',closed_at='2030-01-01 11:59:59' WHERE id=90001");
    sql("UPDATE rooms SET status='CLOSED',closed_at='2030-01-01 12:00:00' WHERE id=90002");
    sql("""
        INSERT INTO rooms(id,host_id,title,code,status,created_at,closed_at) VALUES
        (90003,90001,'Recent','chat03','CLOSED',UTC_TIMESTAMP(),'2030-01-01 12:00:01'),
        (90004,90001,'Open','chat04','OPEN',UTC_TIMESTAMP(),'2030-01-01 11:00:00'),
        (90005,90001,'No close time','chat05','CLOSED',UTC_TIMESTAMP(),NULL)
        """);
    sql("""
        INSERT INTO room_participants(id,room_id,user_id,nickname,joined_at) VALUES
        (90002,90002,90002,'Boundary',UTC_TIMESTAMP()),
        (90003,90003,90002,'Recent',UTC_TIMESTAMP()),
        (90004,90004,90002,'Open',UTC_TIMESTAMP()),
        (90005,90005,90002,'No close time',UTC_TIMESTAMP())
        """);
    sql("""
        INSERT INTO chat_restrictions(room_id,participant_id,restricted_by_user_id,restricted_at)
        SELECT room_id,id,90001,UTC_TIMESTAMP(6) FROM room_participants
        """);
    message(1, 90001, "Expired parent", null);
    professorMessage(2, 90001, "Expired reply", 1L);
    professorMessage(3, 90002, "Boundary", null);
    professorMessage(4, 90003, "Recent", null);
    professorMessage(5, 90004, "Open", null);
    professorMessage(6, 90005, "Null close time", null);
    // Retention must purge both normal and soft-deleted rows in expired rooms,
    // while retaining soft-deleted records in rooms that are still open.
    sql("UPDATE chat_messages SET deleted_at='2030-01-01 01:00:00' WHERE id IN (1,5)");
    sql("INSERT INTO chat_pins VALUES (90001,1,90001,UTC_TIMESTAMP())");
    sql("INSERT INTO chat_pins VALUES (90002,3,90001,UTC_TIMESTAMP())");
    sql("INSERT INTO chat_pins VALUES (90003,4,90001,UTC_TIMESTAMP())");
    rejected(() -> sql("DELETE FROM chat_messages WHERE id=1"));
    var eligible = new ArrayList<Long>();
    try (var s = connection.createStatement(); var r = s.executeQuery("""
        SELECT id FROM rooms WHERE status='CLOSED'
        AND closed_at <= CAST('2030-01-08 12:00:00' AS DATETIME(6)) - INTERVAL 7 DAY ORDER BY id
        """)) {
      while (r.next()) eligible.add(r.getLong(1));
    }
    assertThat(eligible).containsExactly(90001L, 90002L);
    for (long roomId : eligible) {
      sql("UPDATE chat_messages SET parent_id=NULL WHERE room_id=" + roomId);
      while (sql("DELETE FROM chat_messages WHERE room_id=" + roomId + " ORDER BY id LIMIT 1") > 0) {
        // Bounded retention deletes, including automatic pin cleanup.
      }
      while (sql("DELETE FROM chat_restrictions WHERE room_id=" + roomId
          + " ORDER BY participant_id LIMIT 1") > 0) {
        // Restriction cleanup must run even after all messages are gone.
      }
    }
    assertThat(count("chat_pins")).isEqualTo(1);
    assertThat(count("chat_messages")).isEqualTo(3);
    assertThat(count("chat_restrictions")).isEqualTo(3);
    assertThat(count("rooms")).isEqualTo(5);
    assertThat(count("room_participants")).isEqualTo(10);
  }

  private String explainKey(String query) throws SQLException {
    try (var s = connection.createStatement(); var r = s.executeQuery("EXPLAIN " + query)) {
      assertThat(r.next()).isTrue();
      return r.getString("key");
    }
  }

  private List<String> indexColumns(String table, String index) throws SQLException {
    var result = new ArrayList<String>();
    try (var s = connection.prepareStatement("""
        SELECT column_name FROM information_schema.statistics
        WHERE table_schema=DATABASE() AND table_name=? AND index_name=? ORDER BY seq_in_index
        """)) {
      s.setString(1, table);
      s.setString(2, index);
      try (var r = s.executeQuery()) { while (r.next()) result.add(r.getString(1)); }
    }
    return result;
  }

  private void message(long id, long room, String content, Long reply) throws SQLException {
    try (var s = connection.prepareStatement("""
        INSERT INTO chat_messages(id,room_id,sender_participant_id,
        content,parent_id,created_at)
        SELECT ?,p.room_id,p.id,?,?,'2030-01-01 00:00:00'
        FROM room_participants p WHERE p.room_id=? AND p.user_id=90002
        """)) {
      s.setLong(1, id);
      s.setString(2, content);
      s.setObject(3, reply);
      s.setLong(4, room);
      assertThat(s.executeUpdate()).isEqualTo(1);
    }
  }

  private void professorMessage(long id, long room, String content, Long reply) throws SQLException {
    try (var s = connection.prepareStatement("""
        INSERT INTO room_participants(id,room_id,user_id,nickname,joined_at)
        SELECT ?,r.id,r.host_id,'Owner nickname',UTC_TIMESTAMP() FROM rooms r
        WHERE r.id=? AND NOT EXISTS (
          SELECT 1 FROM room_participants p WHERE p.room_id=r.id AND p.user_id=r.host_id)
        """)) {
      s.setLong(1, 100000 + room);
      s.setLong(2, room);
      s.executeUpdate();
    }
    try (var s = connection.prepareStatement("""
        INSERT INTO chat_messages(id,room_id,sender_participant_id,content,parent_id,created_at)
        VALUES (?, ?, ?, ?, ?, '2030-01-01 00:00:00')
        """)) {
      s.setLong(1, id);
      s.setLong(2, room);
      s.setLong(3, 100000 + room);
      s.setString(4, content);
      s.setObject(5, reply);
      s.executeUpdate();
    }
  }

  private int sql(String sql) throws SQLException {
    try (var s = connection.createStatement()) { return s.executeUpdate(sql); }
  }

  private long count(String table) throws SQLException {
    return count(connection, table);
  }

  private long count(Connection source, String table) throws SQLException {
    try (var s = source.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
      r.next();
      return r.getLong(1);
    }
  }

  private void rejected(SqlAction action) {
    assertThatThrownBy(action::run).isInstanceOf(SQLException.class)
        .satisfies(error -> assertThat(((SQLException) error).getSQLState()).matches("23.*|22.*|HY000"));
  }

  @FunctionalInterface private interface SqlAction { void run() throws SQLException; }
}
