package com.sahmhoot.auth.jwt;

import com.sahmhoot.auth.AuthUser;
import com.sahmhoot.user.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 액세스 토큰 발급·검증. subject = userId, claim role. */
@Slf4j
@Component
public class JwtProvider {

  private final SecretKey key;
  private final Duration validity;
  private final Clock clock;

  @Autowired
  public JwtProvider(JwtProperties props) {
    this(props, Clock.systemUTC());
  }

  JwtProvider(JwtProperties props, Clock clock) {
    this.clock = clock;
    this.validity = Duration.ofDays(props.expirationDaysOrDefault());
    if (StringUtils.hasText(props.secret())) {
      this.key = Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8));
    } else {
      byte[] random = new byte[32];
      new SecureRandom().nextBytes(random);
      this.key = Keys.hmacShaKeyFor(random);
      log.warn("app.jwt.secret이 비어 있어 임시 키를 사용합니다. 서버를 다시 켜면 기존 토큰이 무효가 됩니다(로컬 개발 전용).");
    }
  }

  public IssuedToken issue(Long userId, Role role) {
    Instant now = clock.instant();
    Instant expiresAt = now.plus(validity);
    String token =
        Jwts.builder()
            .subject(String.valueOf(userId))
            .claim("role", role.name())
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiresAt))
            .signWith(key)
            .compact();
    return new IssuedToken(token, expiresAt);
  }

  /** 서명·만료가 올바르면 사용자, 아니면 empty. */
  public Optional<AuthUser> parse(String token) {
    if (!StringUtils.hasText(token)) {
      return Optional.empty();
    }
    try {
      Claims claims =
          Jwts.parser()
              .verifyWith(key)
              .clock(() -> Date.from(clock.instant()))
              .build()
              .parseSignedClaims(token)
              .getPayload();
      return Optional.of(
          new AuthUser(Long.valueOf(claims.getSubject()), Role.valueOf(claims.get("role", String.class))));
    } catch (JwtException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /** "Bearer xxx" → "xxx". */
  public static String stripBearer(String header) {
    if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return header.substring(7).trim();
    }
    return null;
  }

  public record IssuedToken(String accessToken, Instant expiresAt) {}
}
