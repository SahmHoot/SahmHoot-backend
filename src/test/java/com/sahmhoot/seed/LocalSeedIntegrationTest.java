package com.sahmhoot.seed;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import com.sahmhoot.SahmhootApplication;
import com.sahmhoot.questionset.entity.QuestionSet;
import com.sahmhoot.questionset.entity.Visibility;
import com.sahmhoot.questionset.repository.ChoiceRepository;
import com.sahmhoot.questionset.repository.QuestionRepository;
import com.sahmhoot.questionset.repository.QuestionSetRepository;
import com.sahmhoot.user.Role;
import com.sahmhoot.user.User;
import com.sahmhoot.user.UserRepository;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

class LocalSeedIntegrationTest {
  private static final List<String> EMAILS = List.of("professor@local.test",
      "student1@local.test", "student2@local.test", "student3@local.test");

  @Test
  void migratesRollsBackSeedsLogsInAndSurvivesAnApplicationRestart() throws Exception {
    try (var prod = start("prod", "--app.seed.enabled=true")) {
      JdbcTemplate jdbc = prod.getBean(JdbcTemplate.class);
      assertThat(prod.getBeansOfType(LocalSeedRunner.class)).isEmpty();
      assertThat(jdbc.queryForObject("""
          SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = true
          """, Long.class)).isEqualTo(1);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email IN (?, ?, ?, ?)",
          Long.class, EMAILS.toArray())).isZero();
      var before = snapshot(jdbc);
      try (var local = start("local")) {
        assertThat(local.getEnvironment().getProperty("app.seed.enabled")).isNull();
        assertThat(local.getBeansOfType(LocalSeedRunner.class)).isEmpty();
        assertThat(snapshot(jdbc)).isEqualTo(before);
      }
      try (var local = start("local", "--app.seed.enabled=false")) {
        assertThat(local.getBeansOfType(LocalSeedRunner.class)).isEmpty();
        assertThat(snapshot(jdbc)).isEqualTo(before);
      }
      var tx = new TransactionTemplate(prod.getBean(PlatformTransactionManager.class));
      var failingChoices = mock(ChoiceRepository.class);
      when(failingChoices.saveAll(any())).thenThrow(new IllegalStateException("seed failure"));
      var failingRunner = new LocalSeedRunner(prod.getBean(UserRepository.class),
          prod.getBean(QuestionSetRepository.class), prod.getBean(QuestionRepository.class),
          failingChoices, prod.getBean(PasswordEncoder.class), prod.getBean(EntityManager.class));
      assertThatThrownBy(() -> tx.executeWithoutResult(status ->
          failingRunner.run(new DefaultApplicationArguments())))
          .isInstanceOf(IllegalStateException.class).hasMessage("seed failure");
      assertThat(snapshot(jdbc)).isEqualTo(before);

      Long[] retainedIds = tx.execute(status -> {
        var users = prod.getBean(UserRepository.class);
        var sets = prod.getBean(QuestionSetRepository.class);
        User existing = users.saveAndFlush(User.create(EMAILS.get(1),
            prod.getBean(PasswordEncoder.class).encode("Existing123!"), "기존 학생", null, Role.STUDENT));
        User other = users.saveAndFlush(User.create("seed52-other@test.invalid",
            "test-only", "다른 교수", null, Role.PROFESSOR));
        QuestionSet otherSet = sets.saveAndFlush(new QuestionSet(other.getId(), LocalSeedRunner.TITLE,
            "보존할 데이터", Visibility.PRIVATE));
        return new Long[] {existing.getId(), other.getId(), otherSet.getId()};
      });
      try {
        Map<String, List<Map<String, Object>>> seeded;
        try (var local = start("local", "--app.seed.enabled=true")) {
          assertThat(local.getBeansOfType(LocalSeedRunner.class)).hasSize(1);
          var mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) local)
              .apply(springSecurity()).build();
          for (int i = 0; i < EMAILS.size(); i++) {
            String password = i == 1 ? "Existing123!" : "Test1234!";
            String token = login(mvc, EMAILS.get(i), password, i == 0 ? "PROFESSOR" : "STUDENT");
            if (i == 2) verifyPublicSet(mvc, token, jdbc);
            String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?",
                String.class, EMAILS.get(i));
            assertThat(hash).startsWith("$2").isNotEqualTo(password);
            assertThat(local.getBean(PasswordEncoder.class).matches(password, hash)).isTrue();
          }
          assertThat(jdbc.queryForObject("SELECT name FROM users WHERE id = ?", String.class,
              retainedIds[0])).isEqualTo("기존 학생");
          assertThat(jdbc.queryForObject("SELECT description FROM question_sets WHERE id = ?",
              String.class, retainedIds[2])).isEqualTo("보존할 데이터");
          seeded = snapshot(jdbc);
        }
        try (var restarted = start("local", "--app.seed.enabled=true")) {
          assertThat(restarted.getBeansOfType(LocalSeedRunner.class)).hasSize(1);
          assertThat(snapshot(jdbc)).isEqualTo(seeded);
        }
        try (var mixed = start("local,prod,test", "--app.seed.enabled=true")) {
          assertThat(mixed.getBeansOfType(LocalSeedRunner.class)).isEmpty();
          assertThat(snapshot(jdbc)).isEqualTo(seeded);
        }
      } finally {
        // 이 테스트가 만든 데이터만 FK 역순으로 정리한다. 고정 ID는 사용하지 않는다.
        jdbc.update("""
            DELETE c FROM choices c JOIN questions q ON q.id = c.question_id
            JOIN question_sets s ON s.id = q.question_set_id
            JOIN users u ON u.id = s.host_id WHERE u.email = ?
            """, EMAILS.getFirst());
        jdbc.update("""
            DELETE q FROM questions q JOIN question_sets s ON s.id = q.question_set_id
            JOIN users u ON u.id = s.host_id WHERE u.email = ?
            """, EMAILS.getFirst());
        jdbc.update("""
            DELETE s FROM question_sets s JOIN users u ON u.id = s.host_id
            WHERE u.email = ? OR u.id = ?
            """, EMAILS.getFirst(), retainedIds[1]);
        jdbc.update("DELETE FROM users WHERE email IN (?, ?, ?, ?) OR id = ?",
            EMAILS.get(0), EMAILS.get(1), EMAILS.get(2), EMAILS.get(3), retainedIds[1]);
      }
      assertThat(snapshot(jdbc)).isEqualTo(before);
    }
  }

  private ConfigurableApplicationContext start(String profiles, String... properties) {
    String url = System.getenv().getOrDefault("TEST_DB_URL",
        "jdbc:mysql://localhost:3306/sahmhoot_test?characterEncoding=UTF-8&connectionTimeZone=UTC");
    var args = new ArrayList<>(List.of(
        "--spring.profiles.active=" + profiles, "--spring.datasource.url=" + url,
        "--server.port=0", "--spring.main.banner-mode=off",
        "--app.jwt.secret=test-only-seed52-jwt-key-0123456789abcdef"));
    args.addAll(List.of(properties));
    return new SpringApplicationBuilder(SahmhootApplication.class).run(args.toArray(String[]::new));
  }

  private Map<String, List<Map<String, Object>>> snapshot(JdbcTemplate jdbc) {
    return Map.of("users", jdbc.queryForList("SELECT * FROM users ORDER BY id"),
        "sets", jdbc.queryForList("SELECT * FROM question_sets ORDER BY id"),
        "questions", jdbc.queryForList("SELECT * FROM questions ORDER BY id"),
        "choices", jdbc.queryForList("SELECT * FROM choices ORDER BY id"));
  }

  private String login(MockMvc mvc, String email, String password, String role) throws Exception {
    String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.user.role").value(role))
        .andReturn().getResponse().getContentAsString();
    return JsonPath.read(body, "$.accessToken");
  }

  private void verifyPublicSet(MockMvc mvc, String token, JdbcTemplate jdbc) throws Exception {
    Long id = jdbc.queryForObject("""
        SELECT s.id FROM question_sets s JOIN users u ON u.id = s.host_id
        WHERE u.email = ? AND s.title = ?
        """, Long.class, EMAILS.getFirst(), LocalSeedRunner.TITLE);
    mvc.perform(get("/api/question-sets/" + id).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk()).andExpect(jsonPath("$.visibility").value("PUBLIC"))
        .andExpect(jsonPath("$.questions.length()").value(2))
        .andExpect(jsonPath("$.questions[0].type").value("MULTIPLE_CHOICE"))
        .andExpect(jsonPath("$.questions[0].choices.length()").value(4))
        .andExpect(jsonPath("$.questions[1].type").value("TRUE_FALSE"))
        .andExpect(jsonPath("$.questions[1].choices.length()").value(2))
        .andExpect(jsonPath("$.questions[1].choices[0].content").value("O"))
        .andExpect(jsonPath("$.questions[1].choices[1].content").value("X"));
    assertThat(jdbc.queryForList("""
        SELECT COUNT(*) FROM choices c JOIN questions q ON q.id = c.question_id
        WHERE q.question_set_id = ? AND c.is_correct = true GROUP BY q.id
        """, Long.class, id)).containsExactly(1L, 1L);
  }
}
