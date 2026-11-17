package com.sahmhoot.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sahmhoot.SahmhootApplication;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** prod 프로필은 application.yml의 공개된 로컬 기본값으로 뜨지 않는다(#20). */
class ProdProfileTest {

  @Test
  void prodDoesNotFallBackToLocalDatabaseAccount() {
    assertThatThrownBy(() -> startProd())
        .hasStackTraceContaining("Access denied for user '${DB_USERNAME}'");
  }

  @Test
  void prodDoesNotFallBackToLocalhostAddresses() {
    assertThatThrownBy(() -> startProd(
            "--spring.datasource.username=sahmhoot_user", "--spring.datasource.password=sahmhoot1!"))
        .hasStackTraceContaining("Could not resolve placeholder");
  }

  private static void startProd(String... properties) {
    String url = System.getenv().getOrDefault("TEST_DB_URL",
        "jdbc:mysql://localhost:3306/sahmhoot_test?characterEncoding=UTF-8&connectionTimeZone=UTC");
    var args = new ArrayList<>(List.of("--spring.profiles.active=prod",
        "--spring.datasource.url=" + url, "--server.port=0", "--spring.main.banner-mode=off",
        "--app.jwt.secret=test-only-prod-profile-key-0123456789abcdef"));
    args.addAll(List.of(properties));
    // 기동에 성공하면 컨텍스트를 닫는다. 이 테스트에서는 실패해야 정상이다.
    new SpringApplicationBuilder(SahmhootApplication.class).run(args.toArray(String[]::new)).close();
  }
}
