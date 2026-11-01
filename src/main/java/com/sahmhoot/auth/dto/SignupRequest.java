package com.sahmhoot.auth.dto;

import com.sahmhoot.user.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignupRequest(
    @NotBlank @Email String email,
    @NotBlank @Pattern(regexp = "\\d{6}", message = "인증 코드는 숫자 6자리입니다.") String code,
    @NotBlank
        @Pattern(
            regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,64}$",
            message = "비밀번호는 영문·숫자를 포함해 8~64자입니다.")
        String password,
    @NotBlank @Size(min = 1, max = 20, message = "이름은 1~20자입니다.") String name,
    @NotNull Role role,
    @Size(max = 20, message = "학번은 20자 이하입니다.") String studentNumber) {}
