package com.sahmhoot.auth.verification;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sahmhoot.auth.exception.ApiException;
import com.sahmhoot.auth.exception.ErrorCode;
import com.sahmhoot.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

class EmailVerificationServiceTest {
  private static final String EMAIL = "member@test.invalid";
  private static final Instant NOW = Instant.parse("2026-11-18T00:00:00Z");
  private final EmailVerificationStore store = new EmailVerificationStore();
  private final VerificationMailSender sender = mock(VerificationMailSender.class);
  private final UserRepository users = mock(UserRepository.class);
  private final EmailVerificationService service = new EmailVerificationService(store, sender, users, "");

  @Test void lateFailureCannotDeleteAReplacementVerification() throws Exception {
    var time = new AtomicReference<>(NOW);
    var clock = mock(Clock.class);
    when(clock.instant()).thenAnswer(call -> time.get());
    service.setClock(clock);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    doAnswer(call -> {
      if (calls.incrementAndGet() == 1) {
        entered.countDown();
        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Blocked SMTP was not released");
        throw new ApiException(ErrorCode.MAIL_SEND_FAILED);
      }
      return null;
    }).when(sender).send(anyString(), anyString());
    var executor = Executors.newSingleThreadExecutor();
    try {
      var first = executor.submit(() -> catchThrowable(() -> service.send(EMAIL)));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      time.set(NOW.plusSeconds(61));
      service.send(EMAIL);
      var replacement = store.get(EMAIL);
      release.countDown();
      assertThat(first.get(5, TimeUnit.SECONDS)).isInstanceOf(ApiException.class);
      assertThat(store.get(EMAIL)).as("예전 발송 실패가 최신 인증 요청을 삭제하면 안 된다").isSameAs(replacement);
      service.confirm(EMAIL, replacement.code());
    } finally {
      release.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test @ResourceLock("java.util.Locale.default")
  void emailNormalizationDoesNotDependOnServerLocale() {
    Locale previous = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      assertThat(EmailVerificationService.normalize(" I@EXAMPLE.COM ")).isEqualTo("i@example.com");
    } finally {
      Locale.setDefault(previous);
    }
  }

  @Test @ResourceLock("java.util.Locale.default")
  void generatedCodeAlwaysUsesSixAsciiDigits() {
    Locale previous = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("ar-EG"));
      service.send(EMAIL);
      assertThat(store.get(EMAIL).code()).matches("[0-9]{6}");
    } finally {
      Locale.setDefault(previous);
    }
  }

  @Test void configuredDomainsAreMatchedWithoutCaseSensitivity() {
    var restricted = new EmailVerificationService(store, sender, users, " TEST.INVALID, OTHER.INVALID ");
    restricted.send(" MEMBER@TEST.INVALID ");
    verify(sender).send(eq(EMAIL), matches("[0-9]{6}"));
  }

  @Test void repeatedConfirmationDoesNotExtendSignupWindow() {
    store.put(EMAIL, new EmailVerificationStore.Entry("123456", NOW, NOW.plusSeconds(600)));
    service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
    service.confirm(EMAIL, "123456");
    service.setClock(Clock.fixed(NOW.plusSeconds(1200), ZoneOffset.UTC));
    service.confirm(EMAIL, "123456");
    service.setClock(Clock.fixed(NOW.plusSeconds(1801), ZoneOffset.UTC));
    assertError(() -> service.verifyForSignup(EMAIL, "123456"), ErrorCode.EMAIL_NOT_VERIFIED);
  }

  @Test void verificationAndSignupShareFiveFailureLimit() {
    service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
    store.put(EMAIL, new EmailVerificationStore.Entry("123456", NOW, NOW.plusSeconds(600)));
    service.confirm(EMAIL, "123456");
    for (int i = 0; i < 4; i++) {
      assertError(() -> service.confirm(EMAIL, "000000"), ErrorCode.VERIFICATION_CODE_INVALID);
    }
    assertError(() -> service.verifyForSignup(EMAIL, "000000"), ErrorCode.VERIFICATION_CODE_INVALID);
    assertThat(store.get(EMAIL)).isNull();
    assertError(() -> service.confirm(EMAIL, "123456"), ErrorCode.VERIFICATION_NOT_FOUND);
  }

  @Test void resendReplacesVerificationAndRespectsThirtySeconds() {
    service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
    var original = new EmailVerificationStore.Entry("123456", NOW, NOW.plusSeconds(600));
    store.put(EMAIL, original);
    service.setClock(Clock.fixed(NOW.plusSeconds(29), ZoneOffset.UTC));
    assertError(() -> service.send(EMAIL), ErrorCode.VERIFICATION_TOO_FREQUENT);
    assertThat(store.get(EMAIL)).isSameAs(original);
    service.setClock(Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC));
    service.send(EMAIL);
    assertThat(store.get(EMAIL)).isNotSameAs(original);
    assertThat(store.get(EMAIL).sentAt).isEqualTo(NOW.plusSeconds(30));
    assertThat(store.get(EMAIL).verifiedAt).isNull();
  }

  @Test void expiredCodeAndExpiredSignupWindowAreRejected() {
    store.put(EMAIL, new EmailVerificationStore.Entry("123456", NOW, NOW.plusSeconds(600)));
    service.setClock(Clock.fixed(NOW.plusSeconds(601), ZoneOffset.UTC));
    assertError(() -> service.confirm(EMAIL, "123456"), ErrorCode.VERIFICATION_CODE_EXPIRED);
    service.setClock(Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
    service.confirm(EMAIL, "123456");
    service.setClock(Clock.fixed(NOW.plusSeconds(1802), ZoneOffset.UTC));
    assertError(() -> service.verifyForSignup(EMAIL, "123456"), ErrorCode.EMAIL_NOT_VERIFIED);
  }

  private static final String RESET_KEY = EmailVerificationService.passwordResetKey(EMAIL);

  @Test void passwordResetMailsOnlyRegisteredEmailButAnswersTheSame() {
    service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
    when(users.existsByEmail(EMAIL)).thenReturn(true);
    service.sendPasswordReset(" Member@Test.Invalid ");
    verify(sender).sendPasswordReset(eq(EMAIL), eq(store.get(RESET_KEY).code()));

    String unknown = "nobody@test.invalid";
    service.sendPasswordReset(unknown);
    verify(sender, never()).sendPasswordReset(eq(unknown), anyString());
    verify(sender, never()).send(anyString(), anyString());
    assertThat(store.get(EmailVerificationService.passwordResetKey(unknown)))
        .as("가입되지 않은 이메일도 재발송 간격 검사를 같게 받도록 항목을 만든다").isNotNull();
    assertError(() -> service.sendPasswordReset(unknown), ErrorCode.VERIFICATION_TOO_FREQUENT);
    assertError(() -> service.sendPasswordReset(EMAIL), ErrorCode.VERIFICATION_TOO_FREQUENT);
  }

  @Test void passwordResetDoesNotRejectRegisteredOrDisallowedDomain() {
    var restricted = new EmailVerificationService(store, sender, users, "allowed.invalid");
    when(users.existsByEmail(EMAIL)).thenReturn(true);
    restricted.sendPasswordReset(EMAIL);
    verify(sender).sendPasswordReset(eq(EMAIL), matches("[0-9]{6}"));
  }

  @Test void signupAndPasswordResetCodesAreSeparate() {
    service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
    store.put(EMAIL, new EmailVerificationStore.Entry("111111", NOW, NOW.plusSeconds(600)));
    store.put(RESET_KEY, new EmailVerificationStore.Entry("222222", NOW, NOW.plusSeconds(600)));
    assertError(() -> service.confirmPasswordReset(EMAIL, "111111"), ErrorCode.VERIFICATION_CODE_INVALID);
    assertError(() -> service.confirm(EMAIL, "222222"), ErrorCode.VERIFICATION_CODE_INVALID);
    service.confirmPasswordReset(EMAIL, "222222");
    assertError(() -> service.verifyForSignup(EMAIL, "111111"), ErrorCode.EMAIL_NOT_VERIFIED);
    var verified = service.verifyForPasswordReset(EMAIL, "222222");
    service.remove(EMAIL, verified);
    assertThat(store.get(RESET_KEY)).as("가입용 remove는 비밀번호 찾기 항목을 지우지 않는다").isSameAs(verified);
    service.removePasswordReset(EMAIL, verified);
    assertThat(store.get(RESET_KEY)).isNull();
    assertThat(store.get(EMAIL)).isNotNull();
  }

  @Test void passwordResetRequiresConfirmationWithinWindowAndKeepsFailureLimit() {
    service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
    store.put(RESET_KEY, new EmailVerificationStore.Entry("123456", NOW, NOW.plusSeconds(600)));
    assertError(() -> service.verifyForPasswordReset(EMAIL, "123456"), ErrorCode.EMAIL_NOT_VERIFIED);
    service.confirmPasswordReset(EMAIL, "123456");
    for (int i = 0; i < 4; i++) {
      assertError(() -> service.verifyForPasswordReset(EMAIL, "000000"), ErrorCode.VERIFICATION_CODE_INVALID);
    }
    assertError(() -> service.confirmPasswordReset(EMAIL, "000000"), ErrorCode.VERIFICATION_CODE_INVALID);
    assertThat(store.get(RESET_KEY)).isNull();
    assertError(() -> service.confirmPasswordReset(EMAIL, "123456"), ErrorCode.VERIFICATION_NOT_FOUND);

    store.put(RESET_KEY, new EmailVerificationStore.Entry("123456", NOW, NOW.plusSeconds(600)));
    service.confirmPasswordReset(EMAIL, "123456");
    service.setClock(Clock.fixed(NOW.plusSeconds(1801), ZoneOffset.UTC));
    assertError(() -> service.verifyForPasswordReset(EMAIL, "123456"), ErrorCode.EMAIL_NOT_VERIFIED);
  }

  @Test void failedPasswordResetMailRemovesOnlyThatEntry() {
    service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
    when(users.existsByEmail(EMAIL)).thenReturn(true);
    doThrow(new ApiException(ErrorCode.MAIL_SEND_FAILED)).when(sender).sendPasswordReset(anyString(), anyString());
    assertError(() -> service.sendPasswordReset(EMAIL), ErrorCode.MAIL_SEND_FAILED);
    assertThat(store.get(RESET_KEY)).isNull();
  }

  private void assertError(Runnable operation, ErrorCode code) {
    assertThatThrownBy(operation::run).isInstanceOfSatisfying(ApiException.class,
        error -> assertThat(error.getErrorCode()).isEqualTo(code));
  }
}
