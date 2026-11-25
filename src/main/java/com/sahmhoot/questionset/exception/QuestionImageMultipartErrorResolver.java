package com.sahmhoot.questionset.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import tools.jackson.databind.json.JsonMapper;

// Multipart parsing and handler mapping can fail before controller-scoped advice is selected.
@Component
@ConditionalOnProperty(prefix = "app.question-images", name = "enabled", havingValue = "true")
class QuestionImageMultipartErrorResolver implements HandlerExceptionResolver, Ordered {
  private static final Pattern UPLOAD = Pattern.compile("/api/question-sets/[^/]+/images");

  private final JsonMapper mapper;

  QuestionImageMultipartErrorResolver(JsonMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public int getOrder() {
    return HIGHEST_PRECEDENCE;
  }

  @Override
  public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response, Object handler,
      Exception exception) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    if (!"POST".equals(request.getMethod()) || !UPLOAD.matcher(path).matches()) return null;
    QuestionSetErrorCode code = switch (exception) {
      case MaxUploadSizeExceededException ignored -> QuestionSetErrorCode.IMAGE_TOO_LARGE;
      case HttpMediaTypeNotSupportedException ignored -> QuestionSetErrorCode.UNSUPPORTED_IMAGE_FORMAT;
      case MultipartException ignored -> QuestionSetErrorCode.VALIDATION_FAILED;
      default -> null;
    };
    if (code == null) return null;
    response.setStatus(code.status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    try {
      mapper.writeValue(response.getOutputStream(), new QuestionSetErrorResponse(code.status, code.name(), code.message, null));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return new ModelAndView();
  }
}
