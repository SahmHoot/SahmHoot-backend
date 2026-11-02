package com.sahmhoot.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sahmhoot.questionset.repository.ChoiceRepository;
import com.sahmhoot.questionset.repository.QuestionRepository;
import com.sahmhoot.questionset.repository.QuestionSetRepository;
import com.sahmhoot.user.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;

class LocalSeedProfileTest {
  @ParameterizedTest
  @ValueSource(strings = {
      "local", "local,dev", "prod", "test", "dev", "local,test", "local,prod",
      "prod,local", "test,local", "local,test,prod", "default"
  })
  void onlyLocalWithoutTestOrProdRegistersRunner(String specification) {
    boolean enabled = specification.equals("local") || specification.equals("local,dev")
        || specification.equals("default");
    new ApplicationContextRunner()
        .withPropertyValues("app.seed.enabled=true")
        .withInitializer(context -> {
          context.getEnvironment().setDefaultProfiles("local");
          if (!specification.equals("default")) {
            context.getEnvironment().setActiveProfiles(specification.split(","));
          }
        })
        .withUserConfiguration(LocalSeedRunner.class)
        .withBean(UserRepository.class, () -> mock(UserRepository.class))
        .withBean(QuestionSetRepository.class, () -> mock(QuestionSetRepository.class))
        .withBean(QuestionRepository.class, () -> mock(QuestionRepository.class))
        .withBean(ChoiceRepository.class, () -> mock(ChoiceRepository.class))
        .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class))
        .withBean(EntityManager.class, () -> mock(EntityManager.class))
        .run(context -> {
          assertThat(context).hasNotFailed();
          if (enabled) assertThat(context).hasSingleBean(LocalSeedRunner.class);
          else assertThat(context).doesNotHaveBean(LocalSeedRunner.class);
        });
  }

  @Test
  void localWithoutSeedSwitchDoesNotRegisterRunner() {
    new ApplicationContextRunner()
        .withPropertyValues("spring.profiles.active=local")
        .withUserConfiguration(LocalSeedRunner.class)
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(LocalSeedRunner.class);
        });
  }

  @Test
  void localWithSeedSwitchDisabledDoesNotRegisterRunner() {
    new ApplicationContextRunner()
        .withPropertyValues("spring.profiles.active=local", "app.seed.enabled=false")
        .withUserConfiguration(LocalSeedRunner.class)
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(LocalSeedRunner.class);
        });
  }
}
