package com.sahmhoot.auth;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProductionAuthSettingsValidatorTest {
  private final String secret = UUID.randomUUID().toString() + UUID.randomUUID();
  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withUserConfiguration(ProductionAuthSettingsValidator.class)
      .withPropertyValues("spring.profiles.active=prod", "spring.mail.host=127.0.0.1",
          "app.mail.from=sender@test.invalid");

  @Test void productionRequiresPersistentSecretWithoutRevealingItsValue() {
    for (String value : new String[] {"", " ", "short"}) {
      runner.withPropertyValues("app.jwt.secret=" + value).run(context -> {
        assertThat(context).hasFailed();
        assertThat(context.getStartupFailure()).hasRootCauseMessage("운영 환경에는 32바이트 이상의 JWT_SECRET 설정이 필요합니다.");
      });
    }
  }

  @Test void productionRequiresMailHostAndSender() {
    runner.withPropertyValues("app.jwt.secret=" + secret, "spring.mail.host=").run(context -> {
      assertThat(context).hasFailed();
      assertThat(context.getStartupFailure()).hasRootCauseMessage("운영 환경에는 MAIL_HOST 설정이 필요합니다.");
    });
    runner.withPropertyValues("app.jwt.secret=" + secret, "app.mail.from=").run(context -> {
      assertThat(context).hasFailed();
      assertThat(context.getStartupFailure()).hasRootCauseMessage("운영 환경에는 MAIL_FROM 설정이 필요합니다.");
    });
  }

  @Test void configuredProductionStartsAndLocalDoesNotRequireProductionSettings() {
    runner.withPropertyValues("app.jwt.secret=" + secret).run(context -> assertThat(context).hasNotFailed());
    runner.withPropertyValues("spring.profiles.active=local").run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context).doesNotHaveBean(ProductionAuthSettingsValidator.class);
    });
  }
}
