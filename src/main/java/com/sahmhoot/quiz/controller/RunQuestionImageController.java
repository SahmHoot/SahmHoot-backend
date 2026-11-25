package com.sahmhoot.quiz.controller;

import com.sahmhoot.quiz.service.RunQuestionImageService;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.question-images", name = "enabled", havingValue = "true")
public class RunQuestionImageController {
  private final RunQuestionImageService images;

  @GetMapping(RunQuestionImageService.PATH)
  public ResponseEntity<byte[]> image(@PathVariable long roomId, @PathVariable long runId,
      @PathVariable long runQuestionId, Principal principal) {
    var download = images.download(roomId, runId, runQuestionId, QuizRunController.extractUserId(principal));
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(download.mimeType()))
        .contentLength(download.content().length)
        .header("X-Content-Type-Options", "nosniff")
        .cacheControl(CacheControl.noStore().cachePrivate())
        .body(download.content());
  }
}
