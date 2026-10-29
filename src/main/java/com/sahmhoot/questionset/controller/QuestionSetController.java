package com.sahmhoot.questionset.controller;

import com.sahmhoot.questionset.auth.QuestionSetCurrentUserResolver;
import com.sahmhoot.questionset.dto.*;
import com.sahmhoot.questionset.service.QuestionSetService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/question-sets")
@RequiredArgsConstructor
public class QuestionSetController {
  private final QuestionSetCurrentUserResolver auth;
  private final QuestionSetService service;

  @GetMapping("/mine")
  public List<QuestionSetSummaryResponse> mine() { return service.mine(auth.requireUser()); }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public QuestionSetSummaryResponse create(@Valid @RequestBody CreateQuestionSetRequest request) {
    return service.create(auth.requireUser(), request);
  }

  @GetMapping("/{setId}")
  public QuestionSetDetailResponse detail(@PathVariable @Positive Long setId) {
    return service.detail(setId, auth.requireUser().id());
  }

  @PatchMapping("/{setId}")
  public QuestionSetSummaryResponse update(@PathVariable @Positive Long setId,
      @Valid @RequestBody UpdateQuestionSetRequest request) {
    return service.update(setId, auth.requireUser().id(), request);
  }

  @DeleteMapping("/{setId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable @Positive Long setId) {
    service.delete(setId, auth.requireUser().id());
  }
}
