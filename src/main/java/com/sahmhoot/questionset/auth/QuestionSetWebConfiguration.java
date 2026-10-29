package com.sahmhoot.questionset.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
@RequiredArgsConstructor
public class QuestionSetWebConfiguration implements WebMvcConfigurer {
  private final QuestionSetAuthenticationInterceptor interceptor;
  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(interceptor).addPathPatterns("/api/question-sets", "/api/question-sets/**");
  }
}
