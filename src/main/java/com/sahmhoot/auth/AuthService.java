package com.sahmhoot.auth;

import com.sahmhoot.auth.dto.AuthResponse;
import com.sahmhoot.auth.dto.LoginRequest;
import com.sahmhoot.auth.dto.SignupRequest;
import com.sahmhoot.auth.jwt.JwtProvider;
import com.sahmhoot.auth.verification.EmailVerificationService;
import com.sahmhoot.common.error.ApiException;
import com.sahmhoot.common.error.ErrorCode;
import com.sahmhoot.user.User;
import com.sahmhoot.user.UserRepository;
import com.sahmhoot.user.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    verificationService.consumeForSignup(email, req.code());
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
    verificationService.remove(email);
    return toResponse(user);
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
