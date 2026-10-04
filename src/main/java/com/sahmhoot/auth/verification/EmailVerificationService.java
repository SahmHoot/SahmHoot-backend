package com.sahmhoot.auth.verification;

import com.sahmhoot.auth.verification.EmailVerificationStore.Entry;
import com.sahmhoot.common.error.ApiException;
import com.sahmhoot.common.error.ErrorCode;
import com.sahmhoot.user.UserRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 05-1·05-2 인증 코드 발송·확인, 05-3 가입 시 재확인. */
@Slf4j
@Service
public class EmailVerificationService {

  static final Duration CODE_TTL = Duration.ofMinutes(10);
  static final Duration RESEND_INTERVAL = Duration.ofSeconds(60);
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
        Arrays.stream(allowedDomains.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
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
    Instant now = clock.instant();
    String code = String.format("%06d", random.nextInt(1_000_000));
    synchronized (this) {
      Entry prev = store.get(email);
      if (prev != null && prev.sentAt.plus(RESEND_INTERVAL).isAfter(now)) {
        throw new ApiException(ErrorCode.VERIFICATION_TOO_FREQUENT);
      }
      // 새로 보내면 이전 항목을 지워 최신 하나만 남긴다.
      store.put(email, new Entry(code, now, now.plus(CODE_TTL)));
    }
    try {
      mailSender.send(email, code);
    } catch (ApiException e) {
      store.remove(email);
      throw e;
    }
  }

  public void confirm(String rawEmail, String code) {
    String email = normalize(rawEmail);
    Entry entry = store.get(email);
    if (entry == null) {
      throw new ApiException(ErrorCode.VERIFICATION_NOT_FOUND);
    }
    synchronized (entry) {
      Instant now = clock.instant();
      if (entry.verifiedAt == null && now.isAfter(entry.expiresAt)) {
        throw new ApiException(ErrorCode.VERIFICATION_CODE_EXPIRED);
      }
      checkCode(email, entry, code);
      if (entry.verifiedAt == null) {
        entry.verifiedAt = now;
      }
    }
  }

  /** 가입 직전 재확인. 통과하면 항목을 지워 재사용을 막는다. */
  public void consumeForSignup(String rawEmail, String code) {
    String email = normalize(rawEmail);
    Entry entry = store.get(email);
    if (entry == null) {
      throw new ApiException(ErrorCode.EMAIL_NOT_VERIFIED);
    }
    synchronized (entry) {
      if (entry.verifiedAt == null
          || clock.instant().isAfter(entry.verifiedAt.plus(SIGNUP_WINDOW))) {
        throw new ApiException(ErrorCode.EMAIL_NOT_VERIFIED);
      }
      checkCode(email, entry, code);
    }
  }

  public void remove(String rawEmail) {
    store.remove(normalize(rawEmail));
  }

  private void checkCode(String email, Entry entry, String code) {
    if (entry.code.equals(code)) {
      return;
    }
    entry.failCount++;
    int left = MAX_FAILURES - entry.failCount;
    if (left <= 0) {
      store.remove(email);
      throw new ApiException(
          ErrorCode.VERIFICATION_CODE_INVALID, "인증 코드가 5회 틀려 무효가 되었습니다. 코드를 다시 받아 주세요.");
    }
    throw new ApiException(
        ErrorCode.VERIFICATION_CODE_INVALID, "인증 코드가 올바르지 않습니다. 남은 시도 " + left + "회");
  }

  public static String normalize(String email) {
    return email == null ? "" : email.trim().toLowerCase();
  }
}
