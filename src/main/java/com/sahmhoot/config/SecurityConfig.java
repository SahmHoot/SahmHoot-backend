package com.sahmhoot.config;

import com.sahmhoot.auth.JwtAuthenticationFilter;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * 세션 없는 JWT 인증 설정. CORS는 두지 않는다(로컬은 Vite 프록시, 운영은 Nginx 같은 도메인).
 *
 * <p>인증 실패(401)·권한 없음(403)도 {@link ErrorResponse} 형식으로 응답한다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

  private final JsonMapper jsonMapper;

  public SecurityConfig(JsonMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
  }

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
                    .requestMatchers(
                        "/api/auth/**",
                        "/ws/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html")
                    .permitAll()
                    .requestMatchers("/api/**")
                    .authenticated()
                    .anyRequest()
                    .permitAll())
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(
                        (request, response, e) -> writeError(response, ErrorCode.UNAUTHORIZED))
                    .accessDeniedHandler(
                        (request, response, e) -> writeError(response, ErrorCode.FORBIDDEN)))
        .addFilterBefore(new JwtAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  private void writeError(HttpServletResponse response, ErrorCode errorCode) throws IOException {
    response.setStatus(errorCode.getStatus().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    jsonMapper.writeValue(response.getOutputStream(), ErrorResponse.of(errorCode));
  }
}
