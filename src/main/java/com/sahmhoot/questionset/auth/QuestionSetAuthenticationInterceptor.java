package com.sahmhoot.questionset.auth;

import com.sahmhoot.questionset.controller.QuestionSetController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Authenticate this domain before MVC parses or validates request bodies.
 * JWT validation remains the responsibility of the shared authentication filter.
 */
@Component
@RequiredArgsConstructor
public class QuestionSetAuthenticationInterceptor implements HandlerInterceptor {
  private final QuestionSetCurrentUserResolver auth;
  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (handler instanceof HandlerMethod method
        && QuestionSetController.class.isAssignableFrom(method.getBeanType())) {
      auth.requireUser();
    }
    return true;
  }
}
