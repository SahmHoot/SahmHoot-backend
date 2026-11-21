package com.sahmhoot.auth.verification;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

/**
 * 이메일 인증 코드 저장소(서버 메모리). 05 API의 "인증 행"이 이 항목이다.
 *
 * <p>DB 테이블 없이 서버 1대 전제. 재시작하면 사라지고 다시 발송하면 된다(05 철중 가이드 확정 규칙).
 */
@Component
public class EmailVerificationStore {

  private final Map<String, Entry> entries = new ConcurrentHashMap<>();

  public Entry get(String email) {
    return entries.get(email);
  }

  public void put(String email, Entry entry) {
    entries.put(email, entry);
  }

  public void remove(String email) {
    entries.remove(email);
  }

  public void remove(String email, Entry expected) {
    entries.remove(email, expected);
  }

  public void removeIf(Predicate<Entry> expired) {
    entries.values().removeIf(expired);
  }

  /** 이메일별 항목. 상태 변경은 EmailVerificationService의 짧은 임계 구역에서 수행한다. */
  public static final class Entry {
    final String code;
    final Instant sentAt;
    final Instant expiresAt;
    Instant verifiedAt;
    int failCount;
    /** 비밀번호 변경 트랜잭션이 이 항목을 쓰는 중(#108). */
    boolean reserved;

    public Entry(String code, Instant sentAt, Instant expiresAt) {
      this.code = code;
      this.sentAt = sentAt;
      this.expiresAt = expiresAt;
    }

    public String code() {
      return code;
    }
  }
}
