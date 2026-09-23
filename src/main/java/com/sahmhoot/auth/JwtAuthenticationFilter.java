package com.sahmhoot.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authorization 헤더의 Bearer JWT로 인증 정보를 채우는 필터.
 *
 * <p>SecurityConfig가 직접 생성해 필터 체인에 넣는다. {@code @Component}로 만들면 서블릿 필터로도 한 번 더 등록되니 붙이지 않는다.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    // TODO(3): JWT 검증 후 SecurityContext 설정
    filterChain.doFilter(request, response);
  }
}
