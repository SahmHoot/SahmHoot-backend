package com.sahmhoot.auth.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * app.jwt.* 설정.
 *
 * @param secret HMAC 비밀키(32바이트 이상). 비우면 로컬 개발용으로 실행할 때마다 임시 키를 만든다
 * @param expirationDays 토큰 유효 기간(05 공통 규칙: 14일, 리프레시 없음)
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, Integer expirationDays) {

  public int expirationDaysOrDefault() {
    return expirationDays == null ? 14 : expirationDays;
  }
}
