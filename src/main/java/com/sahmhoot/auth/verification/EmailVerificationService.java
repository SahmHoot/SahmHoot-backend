package com.sahmhoot.auth.verification;

import com.sahmhoot.auth.verification.EmailVerificationStore.Entry;
import com.sahmhoot.auth.exception.ApiException;
import com.sahmhoot.auth.exception.ErrorCode;
import com.sahmhoot.user.UserRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 인증 상태 변경만 직렬화한다. SMTP 발송은 잠금 밖에서 수행한다. */
@Service
public class EmailVerificationService {

  static final Duration CODE_TTL = Duration.ofMinutes(10);
  static final Duration RESEND_INTERVAL = Duration.ofSeconds(30);
  static final Duration SIGNUP_WINDOW = Duration.ofMinutes(30);
  static final int MAX_FAILURES = 5;

  private final EmailVerificationStore store;
  private final VerificationMailSender mailSender;
  private final UserRepository userRepository;
  private final List<String> allowedDomains;
  private final SecureRandom random = new SecureRandom();
  private Clock clock = Clock.systemUTC();

  @Autowired
  public EmailVerificationService(
      EmailVerificationStore store,
      VerificationMailSender mailSender,
      UserRepository userRepository,
      @Value("${app.auth.allowed-email-domains:}") String allowedDomains) {
    this.store = store;
    this.mailSender = mailSender;
    this.userRepository = userRepository;
    this.allowedDomains =
        Arrays.stream(allowedDomains.split(",")).map(String::trim)
            .map(domain -> domain.toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).toList();
  }

  void setClock(Clock clock) {
    this.clock = clock;
  }

  public void send(String rawEmail) {
    String email = normalize(rawEmail);
    if (!allowedDomains.isEmpty()
        && allowedDomains.stream().noneMatch(d -> email.endsWith("@" + d))) {
      throw new ApiException(ErrorCode.EMAIL_DOMAIN_NOT_ALLOWED);
    }
    if (userRepository.existsByEmail(email)) {
      throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS);
    }
    Entry current = issue(email);
    deliver(email, current, () -> mailSender.send(email, current.code));
  }

  /**
   * 비밀번호 찾기 인증 코드(#108). 가입 여부와 관계없이 같은 검사·같은 응답을 거치고, 가입된 이메일에만 메일을 보낸다.
   *
   * <p>가입되지 않은 이메일에도 항목을 만들어 재발송 간격 응답(429)이 가입 여부를 드러내지 않게 한다. 그 코드는 아무에게도
   * 전달되지 않으므로 확인 단계를 통과할 수 없다.
   */
  public void sendPasswordReset(String rawEmail) {
    String email = normalize(rawEmail);
    String key = passwordResetKey(email);
    Entry current = issue(key);
    if (!userRepository.existsByEmail(email)) {
      return;
    }
    deliver(key, current, () -> mailSender.sendPasswordReset(email, current.code));
  }

  /** 재발송 간격을 확인하고 새 항목으로 바꾼다. 새로 보내면 이전 항목을 지워 최신 하나만 남긴다. */
  private Entry issue(String key) {
    String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
    synchronized (this) {
      Instant now = clock.instant();
      Entry prev = store.get(key);
      if (prev != null && prev.sentAt.plus(RESEND_INTERVAL).isAfter(now)) {
        throw new ApiException(ErrorCode.VERIFICATION_TOO_FREQUENT);
      }
      Entry current = new Entry(code, now, now.plus(CODE_TTL));
      store.put(key, current);
      return current;
    }
  }

  /** SMTP 발송은 잠금 밖에서 한다. 실패하면 이번에 만든 항목만 지운다. */
  private void deliver(String key, Entry current, Runnable send) {
    try {
      send.run();
    } catch (ApiException e) {
      synchronized (this) {
        store.remove(key, current);
      }
      throw e;
    }
  }

  public void confirm(String rawEmail, String code) {
    confirmKey(normalize(rawEmail), code);
  }

  /** 비밀번호 찾기 인증 코드 확인(#108). 규칙은 가입용 confirm과 같다. */
  public void confirmPasswordReset(String rawEmail, String code) {
    confirmKey(passwordResetKey(normalize(rawEmail)), code);
  }

  private synchronized void confirmKey(String key, String code) {
    Entry entry = store.get(key);
    if (entry == null) {
      throw new ApiException(ErrorCode.VERIFICATION_NOT_FOUND);
    }
    Instant now = clock.instant();
    if (entry.verifiedAt == null && now.isAfter(entry.expiresAt)) {
      throw new ApiException(ErrorCode.VERIFICATION_CODE_EXPIRED);
    }
    checkCode(key, entry, code);
    if (entry.verifiedAt == null) {
      entry.verifiedAt = now;
    }
  }

  /** 가입 직전 재확인. 검증한 항목만 가입 트랜잭션 커밋 후 제거한다. */
  public Entry verifyForSignup(String rawEmail, String code) {
    return verifyKey(normalize(rawEmail), code);
  }

  /** 비밀번호 변경 직전 재확인(#108). 검증한 항목은 변경 트랜잭션 커밋 후 removePasswordReset으로 제거한다. */
  public Entry verifyForPasswordReset(String rawEmail, String code) {
    return verifyKey(passwordResetKey(normalize(rawEmail)), code);
  }

  private synchronized Entry verifyKey(String key, String code) {
    Entry entry = store.get(key);
    if (entry == null) {
      throw new ApiException(ErrorCode.EMAIL_NOT_VERIFIED);
    }
    if (entry.verifiedAt == null
        || clock.instant().isAfter(entry.verifiedAt.plus(SIGNUP_WINDOW))) {
      throw new ApiException(ErrorCode.EMAIL_NOT_VERIFIED);
    }
    checkCode(key, entry, code);
    return entry;
  }

  public synchronized void remove(String rawEmail, Entry expected) {
    store.remove(normalize(rawEmail), expected);
  }

  public synchronized void removePasswordReset(String rawEmail, Entry expected) {
    store.remove(passwordResetKey(normalize(rawEmail)), expected);
  }

  /** 비밀번호 찾기 항목의 저장소 키. 공백은 이메일에 들어갈 수 없어 가입용 키(이메일 그대로)와 겹치지 않는다. */
  public static String passwordResetKey(String normalizedEmail) {
    return "password-reset " + normalizedEmail;
  }

  private void checkCode(String key, Entry entry, String code) {
    if (entry.code.equals(code)) {
      return;
    }
    entry.failCount++;
    int left = MAX_FAILURES - entry.failCount;
    if (left <= 0) {
      store.remove(key, entry);
      throw new ApiException(
          ErrorCode.VERIFICATION_CODE_INVALID, "인증 코드가 5회 틀려 무효가 되었습니다. 코드를 다시 받아 주세요.");
    }
    throw new ApiException(
        ErrorCode.VERIFICATION_CODE_INVALID, "인증 코드가 올바르지 않습니다. 남은 시도 " + left + "회");
  }

  public static String normalize(String email) {
    return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
  }
}
