package com.sahmhoot.quiz.controller;

import com.sahmhoot.quiz.exception.BusinessException;
import com.sahmhoot.quiz.exception.ErrorCode;
import com.sahmhoot.quiz.dto.StartQuizRequest;
import com.sahmhoot.quiz.dto.StartQuizResponse;
import com.sahmhoot.quiz.service.QuizCoreService;
import jakarta.validation.Valid;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 1. Quiz Core: 퀴즈 시작 / 중단 / 결과 닫기 REST 컨트롤러.
 * 05 API 명세 23~25번 엔드포인트를 제공한다.
 */
@RestController
@RequestMapping("/api/rooms/{roomId}")
@RequiredArgsConstructor
public class QuizRunController {

  private final QuizCoreService quizCoreService;

  /**
   * 23. POST /api/rooms/{roomId}/quiz-runs — 퀴즈 시작 (방 소유자 권한)
   */
  @PostMapping("/quiz-runs")
  @ResponseStatus(HttpStatus.CREATED)
  public StartQuizResponse startQuiz(
      @PathVariable("roomId") Long roomId,
      @Valid @RequestBody StartQuizRequest request,
      Principal principal
  ) {
    Long hostUserId = extractUserId(principal);
    return quizCoreService.startQuiz(roomId, request, hostUserId);
  }

  /**
   * 24. DELETE /api/rooms/{roomId}/quiz-runs/{runId} — 퀴즈 중단 (방 소유자 권한)
   */
  @DeleteMapping("/quiz-runs/{runId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void abortQuiz(
      @PathVariable("roomId") Long roomId,
      @PathVariable("runId") Long runId,
      Principal principal
  ) {
    Long hostUserId = extractUserId(principal);
    quizCoreService.abortQuiz(roomId, runId, hostUserId);
  }

  /**
   * 25. POST /api/rooms/{roomId}/wait — 결과 닫기 / 대기로 (방 소유자 권한)
   */
  @PostMapping("/wait")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void closeResult(
      @PathVariable("roomId") Long roomId,
      Principal principal
  ) {
    Long hostUserId = extractUserId(principal);
    quizCoreService.closeResult(roomId, hostUserId);
  }

  /**
   * 인증된 Principal에서 userId를 추출한다.
   * 인증 정보가 없거나 숫자로 파싱할 수 없으면 UNAUTHORIZED 예외를 던진다.
   */
  private Long extractUserId(Principal principal) {
    if (principal == null || principal.getName() == null) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "인증 정보가 없습니다.");
    }
    try {
      return Long.parseLong(principal.getName());
    } catch (NumberFormatException e) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "유효하지 않은 인증 정보입니다.");
    }
  }
}
