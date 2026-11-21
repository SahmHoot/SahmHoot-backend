package com.sahmhoot.auth;

import com.sahmhoot.auth.dto.AuthResponse;
import com.sahmhoot.auth.dto.LoginRequest;
import com.sahmhoot.auth.dto.PasswordResetRequest;
import com.sahmhoot.auth.dto.SignupRequest;
import com.sahmhoot.auth.jwt.JwtProvider;
import com.sahmhoot.auth.verification.EmailVerificationService;
import com.sahmhoot.auth.exception.ApiException;
import com.sahmhoot.auth.exception.ErrorCode;
import com.sahmhoot.user.User;
import com.sahmhoot.user.UserRepository;
import com.sahmhoot.user.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class AuthService {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtProvider jwtProvider;
  private final EmailVerificationService verificationService;

  @Transactional
  public AuthResponse signup(SignupRequest req) {
    String email = EmailVerificationService.normalize(req.email());
    var verification = verificationService.verifyForSignup(email, req.code());
    if (userRepository.existsByEmail(email)) {
      throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS);
    }
    String studentNumber =
        StringUtils.hasText(req.studentNumber()) ? req.studentNumber().trim() : null;
    if (studentNumber != null && userRepository.existsByStudentNumber(studentNumber)) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, "이미 등록된 학번입니다.");
    }
    User user =
        userRepository.save(
            User.create(
                email,
                passwordEncoder.encode(req.password()),
                req.name().trim(),
                studentNumber,
                req.role()));
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override public void afterCommit() {
        verificationService.remove(email, verification);
      }
    });
    return toResponse(user);
  }

  /**
   * 비밀번호 찾기 마지막 단계(#108). 인증을 마친 코드를 예약하고 새 비밀번호를 저장한다. 커밋되면 항목을 지워 같은 코드를 다시
   * 쓰지 못하게 하고, 롤백되면 예약만 풀어 다시 시도할 수 있게 한다. 가입되지 않은 이메일은 인증 단계를 통과할 수 없다.
   */
  @Transactional
  public void resetPassword(PasswordResetRequest req) {
    String email = EmailVerificationService.normalize(req.email());
    var verification = verificationService.reservePasswordReset(email, req.code());
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override public void afterCompletion(int status) {
        if (status == STATUS_COMMITTED) {
          verificationService.removePasswordReset(email, verification);
        } else {
          verificationService.releasePasswordReset(verification);
        }
      }
    });
    // 메일을 보낸 주소(DB에 저장된 주소와 정확히 같은 주소)의 계정만 바꾼다.
    User user =
        userRepository
            .findByEmail(email)
            .filter(u -> u.getEmail().equals(email))
            .orElseThrow(() -> new ApiException(ErrorCode.EMAIL_NOT_VERIFIED));
    user.changePassword(passwordEncoder.encode(req.newPassword()));
  }

  @Transactional(readOnly = true)
  public AuthResponse login(LoginRequest req) {
    User user =
        userRepository
            .findByEmail(EmailVerificationService.normalize(req.email()))
            .filter(u -> passwordEncoder.matches(req.password(), u.getPasswordHash()))
            .orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS));
    return toResponse(user);
  }

  private AuthResponse toResponse(User user) {
    JwtProvider.IssuedToken token = jwtProvider.issue(user.getId(), user.getRole());
    return new AuthResponse(token.accessToken(), token.expiresAt(), UserResponse.from(user));
  }
}
