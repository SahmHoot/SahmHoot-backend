package com.sahmhoot.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** users 테이블. 시간은 UTC로 저장한다. */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true)
  private String email;

  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  @Column(nullable = false, length = 50)
  private String name;

  @Column(name = "student_number", length = 20, unique = true)
  private String studentNumber;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private Role role;

  @Column(name = "email_verified", nullable = false)
  private boolean emailVerified;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  public static User create(
      String email, String passwordHash, String name, String studentNumber, Role role) {
    User u = new User();
    u.email = email;
    u.passwordHash = passwordHash;
    u.name = name;
    u.studentNumber = studentNumber;
    u.role = role;
    u.emailVerified = true;
    u.createdAt = LocalDateTime.now(ZoneOffset.UTC);
    u.updatedAt = u.createdAt;
    return u;
  }

  /** 비밀번호 찾기(#108). 이미 발급된 토큰은 만료 전까지 유효하다(서버가 토큰 무효화 기능을 두지 않음). */
  public void changePassword(String passwordHash) {
    this.passwordHash = passwordHash;
    this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
  }
}
