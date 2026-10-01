package com.sahmhoot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 임시 개발 초기 보안 설정.
 *
 * 현재: /api/** 요청을 모두 허용 (JWT 구현 전)
 * 기능별 API 개발/테스트를 위해 인증을 요구하지 않음.
 *
 * TODO: Auth 담당자가 JWT 인증을 구현할 때 현재 임시 permitAll 정책을
 * 실제 인증/인가 정책으로 교체한다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth.requestMatchers("/api/**").permitAll()
            .anyRequest()
            .permitAll());
    return http.build();
  }
}
