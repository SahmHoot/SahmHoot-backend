package com.sahmhoot.auth.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authorization: Bearer 토큰이 유효하면 SecurityContext에 AuthUser를 넣는다. 없거나 틀리면 그냥 통과(인가 단계에서 401). */
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private final JwtProvider jwtProvider;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String token = JwtProvider.stripBearer(request.getHeader(HttpHeaders.AUTHORIZATION));
    jwtProvider
        .parse(token)
        .ifPresent(
            user -> {
              var auth =
                  new UsernamePasswordAuthenticationToken(
                      user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
              SecurityContextHolder.getContext().setAuthentication(auth);
            });
    chain.doFilter(request, response);
  }
}
