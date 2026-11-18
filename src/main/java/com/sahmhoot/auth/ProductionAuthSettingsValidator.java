package com.sahmhoot.auth;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 운영 서버가 임시 JWT 키나 메일 설정 누락 상태로 기동되는 것을 막는다. */
@Component
@Profile("prod")
@RequiredArgsConstructor
public class ProductionAuthSettingsValidator {
  private final Environment environment;

  @PostConstruct
  void validate() {
    String secret = environment.getProperty("app.jwt.secret");
    if (!StringUtils.hasText(secret) || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
      throw new IllegalStateException("운영 환경에는 32바이트 이상의 JWT_SECRET 설정이 필요합니다.");
    }
    requireText("spring.mail.host", "운영 환경에는 MAIL_HOST 설정이 필요합니다.");
    requireText("app.mail.from", "운영 환경에는 MAIL_FROM 설정이 필요합니다.");
  }

  private void requireText(String property, String message) {
    if (!StringUtils.hasText(environment.getProperty(property))) {
      throw new IllegalStateException(message);
    }
  }
}
