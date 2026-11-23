package com.sahmhoot.auth.verification;

import static org.assertj.core.api.Assertions.*;

import com.sahmhoot.auth.AuthService;
import com.sahmhoot.auth.dto.PasswordResetRequest;
import com.sahmhoot.auth.exception.ApiException;
import com.sahmhoot.auth.exception.ErrorCode;
import com.sahmhoot.user.Role;
import com.sahmhoot.user.User;
import com.sahmhoot.user.UserRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 비밀번호 찾기(#108) 마지막 단계의 커밋·롤백과 인증 항목 처리. 실제 커밋을 쓰므로 테스트 트랜잭션을 두지 않는다. */
@SpringBootTest
@ActiveProfiles("test")
class PasswordResetTransactionTest {
  @Autowired EmailVerificationStore store;
  @Autowired EmailVerificationService verification;
  @Autowired AuthService auth;
  @Autowired UserRepository users;
  @Autowired PasswordEncoder encoder;
  @Autowired PlatformTransactionManager manager;
  private final String email = UUID.randomUUID() + "@test.invalid";
  private String key;

  @BeforeEach void user() {
    key = EmailVerificationService.passwordResetKey(email);
    users.save(User.create(email, encoder.encode("Old12345"), "재설정", null, Role.STUDENT));
  }

  @AfterEach void cleanup() {
    store.remove(key);
    users.findByEmail(email).ifPresent(users::delete);
  }

  @Test void rollbackReleasesReservationAndCommitConsumesIt() {
    var entry = verified();
    new TransactionTemplate(manager).executeWithoutResult(status -> {
      auth.resetPassword(request("New12345"));
      status.setRollbackOnly();
    });
    assertThat(encoder.matches("Old12345", users.findByEmail(email).orElseThrow().getPasswordHash())).isTrue();
    assertThat(store.get(key)).as("롤백되면 같은 코드로 다시 시도할 수 있어야 한다").isSameAs(entry);
    assertThat(entry.reserved).isFalse();

    auth.resetPassword(request("New12345"));
    assertThat(encoder.matches("New12345", users.findByEmail(email).orElseThrow().getPasswordHash())).isTrue();
    assertThat(store.get(key)).isNull();
    assertThatThrownBy(() -> auth.resetPassword(request("Other123")))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED));
  }

  @Test void sameCodeCannotChangePasswordAgainWhileTheFirstChangeIsInProgress() {
    verified();
    new TransactionTemplate(manager).executeWithoutResult(status -> {
      auth.resetPassword(request("First123"));
      // 첫 변경이 커밋되기 전에 같은 코드로 들어온 두 번째 요청
      var separate = new TransactionTemplate(manager);
      separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      assertThatThrownBy(() -> separate.executeWithoutResult(inner -> auth.resetPassword(request("Second12")))).isInstanceOfSatisfying(ApiException.class,
          e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED));
    });
    assertThat(encoder.matches("First123", users.findByEmail(email).orElseThrow().getPasswordHash())).isTrue();
    assertThat(store.get(key)).isNull();
  }

  private EmailVerificationStore.Entry verified() {
    var entry = new EmailVerificationStore.Entry("123456", Instant.now(), Instant.now().plusSeconds(600));
    store.put(key, entry);
    verification.confirmPasswordReset(email, "123456");
    return entry;
  }

  private PasswordResetRequest request(String password) {
    return new PasswordResetRequest(email, "123456", password);
  }
}
