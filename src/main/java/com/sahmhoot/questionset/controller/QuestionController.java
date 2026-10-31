package com.sahmhoot.questionset.controller;

import com.sahmhoot.questionset.auth.QuestionSetCurrentUserResolver;
import com.sahmhoot.questionset.dto.*;
import com.sahmhoot.questionset.service.QuestionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/question-sets/{setId}/questions")
@RequiredArgsConstructor
public class QuestionController {
  private final QuestionSetCurrentUserResolver auth;
  private final QuestionService service;

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public QuestionResponse add(@PathVariable @Positive Long setId,
      @Valid @RequestBody QuestionWriteRequest request) {
    return service.add(setId, auth.requireUser().id(), request);
  }

  @PutMapping("/{questionId}")
  public QuestionResponse update(@PathVariable @Positive Long setId,
      @PathVariable @Positive Long questionId, @Valid @RequestBody QuestionWriteRequest request) {
    return service.update(setId, questionId, auth.requireUser().id(), request);
  }

  @DeleteMapping("/{questionId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable @Positive Long setId, @PathVariable @Positive Long questionId) {
    service.delete(setId, questionId, auth.requireUser().id());
  }
}
