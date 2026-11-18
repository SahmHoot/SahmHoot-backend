package com.sahmhoot.auth.verification;

import static org.assertj.core.api.Assertions.*;

import com.sahmhoot.auth.AuthService;
import com.sahmhoot.auth.dto.SignupRequest;
import com.sahmhoot.user.Role;
import com.sahmhoot.user.UserRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
class SignupVerificationTransactionTest {
  @Autowired EmailVerificationStore store;
  @Autowired EmailVerificationService verification;
  @Autowired AuthService auth;
  @Autowired UserRepository users;
  @Autowired PlatformTransactionManager manager;
  private final String email = UUID.randomUUID() + "@test.invalid";

  @AfterEach void cleanup() {
    store.remove(email);
    users.findByEmail(email).ifPresent(users::delete);
  }

  @Test void rollbackPreservesVerificationForRetryAndSuccessfulCommitConsumesIt() {
    var entry = verified();
    var transaction = new TransactionTemplate(manager);
    transaction.executeWithoutResult(status -> {
      auth.signup(request());
      status.setRollbackOnly();
    });
    assertThat(users.existsByEmail(email)).isFalse();
    assertThat(store.get(email)).as("가입 DB 롤백 시 인증 기록은 유지해야 한다").isSameAs(entry);
    auth.signup(request());
    assertThat(users.existsByEmail(email)).isTrue();
    assertThat(store.get(email)).isNull();
  }

  @Test void earlierSignupCommitCannotRemoveNewerVerification() {
    verified();
    var replacement = new EmailVerificationStore.Entry("654321", Instant.now(), Instant.now().plusSeconds(600));
    new TransactionTemplate(manager).executeWithoutResult(status -> {
      auth.signup(request());
      store.put(email, replacement);
    });
    assertThat(store.get(email)).isSameAs(replacement);
  }

  private EmailVerificationStore.Entry verified() {
    var entry = new EmailVerificationStore.Entry("123456", Instant.now(), Instant.now().plusSeconds(600));
    store.put(email, entry);
    verification.confirm(email, "123456");
    return entry;
  }

  private SignupRequest request() {
    return new SignupRequest(email, "123456", "Test1234", "가입테스트", Role.STUDENT, null);
  }
}
