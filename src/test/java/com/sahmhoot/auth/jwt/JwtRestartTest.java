package com.sahmhoot.auth.jwt;

import static org.assertj.core.api.Assertions.*;

import com.sahmhoot.auth.AuthUser;
import com.sahmhoot.user.Role;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtRestartTest {
  private final Clock clock = Clock.fixed(Instant.parse("2026-11-18T00:00:00Z"), ZoneOffset.UTC);

  @Test void sameSecretVerifiesPreviouslyIssuedTokenAfterProviderRecreation() {
    var properties = new JwtProperties(UUID.randomUUID().toString() + UUID.randomUUID(), 14);
    var first = new JwtProvider(properties, clock);
    var token = first.issue(42L, Role.STUDENT);
    var restarted = new JwtProvider(properties, clock);
    assertThat(restarted.parse(token.accessToken())).contains(new AuthUser(42L, Role.STUDENT));
    var rotated = new JwtProvider(new JwtProperties(UUID.randomUUID().toString() + UUID.randomUUID(), 14), clock);
    assertThat(rotated.parse(token.accessToken())).isEmpty();
  }
}
