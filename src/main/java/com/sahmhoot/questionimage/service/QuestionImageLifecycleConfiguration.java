package com.sahmhoot.questionimage.service;

import com.sahmhoot.questionimage.storage.ImageStorage;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("(local | prod) & !test")
@ConditionalOnProperty(prefix = "app.question-images", name = "enabled", havingValue = "true")
class QuestionImageLifecycleConfiguration {
  @Bean
  QuestionImageLifecycle questionImageLifecycle(QuestionImageValidator validator,
      QuestionImageLifecycleRepository repository, ImageStorage storage) {
    return new QuestionImageLifecycle(validator, repository, storage, Clock.systemUTC());
  }

  @Bean
  Cleanup cleanup(QuestionImageLifecycle lifecycle) {
    return new Cleanup(lifecycle);
  }

  static class Cleanup {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(Cleanup.class);
    private final QuestionImageLifecycle lifecycle;

    Cleanup(QuestionImageLifecycle lifecycle) {
      this.lifecycle = lifecycle;
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    void clean() {
      int failures = lifecycle.cleanExpired(50).size();
      if (failures > 0) {
        LOGGER.warn("Question image cleanup will retry {} failed deletions", failures);
      }
    }
  }
}
