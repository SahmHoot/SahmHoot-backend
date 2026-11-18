package com.sahmhoot.auth.verification;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class MailSettingsTest {
  @Test void productionUsesPostfixAndAllTimeoutsAreFinite() {
    new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
        .withPropertyValues("spring.profiles.active=prod")
        .run(context -> {
          assertThat(context).hasNotFailed();
          var sender = context.getBean(JavaMailSenderImpl.class);
          assertThat(sender.getHost()).isEqualTo("127.0.0.1");
          assertThat(sender.getPort()).isEqualTo(25);
          assertThat(sender.getJavaMailProperties())
              .containsEntry("mail.smtp.auth", "false")
              .containsEntry("mail.smtp.starttls.enable", "false")
              .containsEntry("mail.smtp.starttls.required", "false")
              .containsEntry("mail.smtp.connectiontimeout", "5000")
              .containsEntry("mail.smtp.timeout", "5000")
              .containsEntry("mail.smtp.writetimeout", "5000");
        });
  }
}
