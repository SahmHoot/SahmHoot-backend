package com.sahmhoot.config;

import com.sahmhoot.common.error.ErrorCode;
import com.sahmhoot.common.error.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.json.JsonMapper;

/**
 * 세션 없는 인증 설정의 공통 틀. CORS는 두지 않는다(로컬은 Vite 프록시, 운영은 Nginx 같은 도메인).
 *
 * <p>인증 실패(401)·권한 없음(403)도 {@link ErrorResponse} 형식으로 응답한다.
 *
 * <p>보안 필터를 추가할 때는 {@code @Component}로 만들지 말고 이 클래스에서 {@code new}로 생성해 체인에 넣는다. 빈으로 등록하면 Spring
 * Boot가 서블릿 필터로도 한 번 더 등록해 요청마다 두 번 실행된다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  private final JsonMapper jsonMapper;

  public SecurityConfig(JsonMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
  }

  // TODO(3): JWT 필터 등록, /api/auth/** 허용, PasswordEncoder 빈
  // TODO(1): /ws/** 허용
  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.GET, "/api/health")
                    .permitAll()
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(
                        (request, response, e) -> writeError(response, ErrorCode.UNAUTHORIZED))
                    .accessDeniedHandler(
                        (request, response, e) -> writeError(response, ErrorCode.FORBIDDEN)));
    return http.build();
  }

  private void writeError(HttpServletResponse response, ErrorCode errorCode) throws IOException {
    response.setStatus(errorCode.getStatus().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    jsonMapper.writeValue(response.getOutputStream(), ErrorResponse.of(errorCode));
  }
}
