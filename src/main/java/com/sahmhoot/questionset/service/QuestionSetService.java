package com.sahmhoot.questionset.service;

import com.sahmhoot.questionset.auth.QuestionSetCurrentUser;
import com.sahmhoot.questionset.dto.*;
import com.sahmhoot.questionset.entity.*;
import com.sahmhoot.questionset.exception.*;
import com.sahmhoot.questionset.repository.*;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class QuestionSetService {
  private final QuestionSetRepository sets;
  private final QuestionRepository questions;
  private final ChoiceRepository choices;
  private final QuestionSetQueryRepository query;

  @Transactional(readOnly = true)
  public List<QuestionSetSummaryResponse> mine(QuestionSetCurrentUser user) {
    requireProfessor(user);
    return query.mine(user.id());
  }

  @Transactional
  public QuestionSetSummaryResponse create(QuestionSetCurrentUser user, CreateQuestionSetRequest request) {
    requireProfessor(user);
    QuestionSet set = sets.saveAndFlush(new QuestionSet(user.id(), request.title(),
        request.description(), request.visibility() == null ? Visibility.PRIVATE : request.visibility()));
    return query.summary(set.getId());
  }

  @Transactional(readOnly = true)
  public QuestionSetDetailResponse detail(Long setId, Long userId) {
    QuestionSet set = sets.findById(setId)
        .orElseThrow(() -> new QuestionSetException(QuestionSetErrorCode.QUESTION_SET_NOT_FOUND));
    if (!set.getHostId().equals(userId) && set.getVisibility() != Visibility.PUBLIC) {
      throw new QuestionSetException(QuestionSetErrorCode.FORBIDDEN);
    }
    var all = questions.findByQuestionSetIdOrderByOrderNoAsc(setId);
    Map<Long, List<ChoiceResponse>> byQuestion = all.isEmpty() ? Map.of()
        : choices.findByQuestionIdInOrderByOrderNoAsc(all.stream().map(Question::getId).toList())
            .stream().collect(Collectors.groupingBy(c -> c.getQuestion().getId(),
                Collectors.mapping(ChoiceResponse::from, Collectors.toList())));
    return new QuestionSetDetailResponse(set.getId(), set.getTitle(), set.getVisibility(),
        set.getDescription(), query.ownerName(set.getHostId()), all.stream()
            .map(q -> QuestionResponse.from(q, byQuestion.getOrDefault(q.getId(), List.of()))).toList());
  }

  @Transactional
  public QuestionSetSummaryResponse update(Long setId, Long userId, UpdateQuestionSetRequest request) {
    QuestionSet set = owned(setId, userId);
    set.update(request.isTitlePresent() ? request.getTitle() : set.getTitle(),
        request.isDescriptionPresent() ? request.getDescription() : set.getDescription(),
        request.isVisibilityPresent() ? request.getVisibility() : set.getVisibility());
    sets.flush();
    return query.summary(setId);
  }

  @Transactional
  public void delete(Long setId, Long userId) {
    QuestionSet set = owned(setId, userId);
    if (query.used(setId)) throw new QuestionSetException(QuestionSetErrorCode.QUESTION_SET_IN_USE);
    choices.deleteForSet(setId);
    choices.flush();
    questions.deleteAll(questions.findByQuestionSetIdOrderByOrderNoAsc(setId));
    questions.flush();
    try {
      sets.delete(set);
      sets.flush();
    } catch (DataIntegrityViolationException e) {
      // A concurrent quiz start can establish the FK after the initial history check.
      if (referencesQuizRun(e)) {
        throw new QuestionSetException(QuestionSetErrorCode.QUESTION_SET_IN_USE);
      }
      throw e;
    }
  }

  private QuestionSet owned(Long id, Long userId) {
    QuestionSet set = sets.findLockedById(id)
        .orElseThrow(() -> new QuestionSetException(QuestionSetErrorCode.QUESTION_SET_NOT_FOUND));
    if (!set.getHostId().equals(userId)) throw new QuestionSetException(QuestionSetErrorCode.FORBIDDEN);
    return set;
  }

  private void requireProfessor(QuestionSetCurrentUser user) {
    if (!user.professor()) throw new QuestionSetException(QuestionSetErrorCode.FORBIDDEN);
  }

  private boolean referencesQuizRun(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof java.sql.SQLException sql && sql.getErrorCode() == 1451
          && sql.getMessage().contains("quiz_runs")) return true;
    }
    return false;
  }
}
