package com.sahmhoot.questionimage.storage;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("local & !prod & !test")
@ConditionalOnProperty(prefix = "app.question-images", name = "enabled", havingValue = "true")
class LocalImageStorageConfiguration {
  @Bean
  ImageStorage imageStorage(@Value("${app.question-images.local-directory:${user.home}/.sahmhoot/question-images}")
      Path directory) {
    return new LocalImageStorage(directory);
  }
}
