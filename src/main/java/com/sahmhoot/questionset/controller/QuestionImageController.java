package com.sahmhoot.questionset.controller;

import com.sahmhoot.questionset.auth.QuestionSetCurrentUserResolver;
import com.sahmhoot.questionset.exception.QuestionSetErrorCode;
import com.sahmhoot.questionset.exception.QuestionSetException;
import com.sahmhoot.questionset.service.QuestionImageService;
import com.sahmhoot.questionset.service.port.QuestionImageAccess;
import jakarta.validation.constraints.Positive;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/question-sets/{setId}")
@ConditionalOnProperty(prefix = "app.question-images", name = "enabled", havingValue = "true")
public class QuestionImageController {
  private final QuestionSetCurrentUserResolver auth;
  private final QuestionImageService images;

  @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public QuestionImageAccess.Upload upload(@PathVariable @Positive long setId, MultipartHttpServletRequest request)
      throws IOException {
    var user = auth.requireUser();
    var files = request.getFiles("file");
    if (request.getMultiFileMap().size() != 1 || files.size() != 1 || files.getFirst().isEmpty()) {
      throw new QuestionSetException(QuestionSetErrorCode.VALIDATION_FAILED);
    }
    var file = files.getFirst();
    try (var stream = file.getInputStream()) {
      return images.upload(setId, user, stream, file.getContentType());
    }
  }

  @GetMapping("/questions/{questionId}/image")
  public ResponseEntity<byte[]> image(@PathVariable @Positive long setId, @PathVariable @Positive long questionId) {
    var download = images.download(setId, questionId, auth.requireUser().id());
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(download.mimeType()))
        .contentLength(download.content().length)
        .header("X-Content-Type-Options", "nosniff")
        .cacheControl(CacheControl.noStore().cachePrivate())
        .body(download.content());
  }
}
