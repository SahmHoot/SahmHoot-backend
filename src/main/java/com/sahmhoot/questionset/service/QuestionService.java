package com.sahmhoot.questionset.service;

import com.sahmhoot.questionset.dto.*;
import com.sahmhoot.questionset.entity.*;
import com.sahmhoot.questionset.exception.*;
import com.sahmhoot.questionset.repository.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class QuestionService {
  private final QuestionSetRepository sets;
  private final QuestionRepository questions;
  private final ChoiceRepository choices;

  @Transactional
  public QuestionResponse add(Long setId, Long userId, QuestionWriteRequest request) {
    QuestionSet set = owned(setId, userId, QuestionSetErrorCode.QUESTION_SET_NOT_FOUND);
    var existing = questions.findByQuestionSetIdOrderByOrderNoAsc(setId);
    int order = existing.isEmpty() ? 1 : Math.addExact(existing.getLast().getOrderNo(), 1);
    Question q = questions.saveAndFlush(new Question(set, request.type(), request.content(),
        request.secondsOrDefault(), order));
    var response = replaceChoices(q, request);
    set.touch();
    return response;
  }

  @Transactional
  public QuestionResponse update(Long setId, Long questionId, Long userId, QuestionWriteRequest request) {
    QuestionSet set = owned(setId, userId, QuestionSetErrorCode.QUESTION_NOT_FOUND);
    Question q = question(setId, questionId);
    q.update(request.type(), request.content(), request.secondsOrDefault());
    choices.deleteForQuestion(questionId);
    choices.flush();
    var response = replaceChoices(q, request);
    set.touch();
    return response;
  }

  @Transactional
  public void delete(Long setId, Long questionId, Long userId) {
    QuestionSet set = owned(setId, userId, QuestionSetErrorCode.QUESTION_NOT_FOUND);
    Question q = question(setId, questionId);
    int removedOrder = q.getOrderNo();
    choices.deleteForQuestion(questionId);
    choices.flush();
    questions.delete(q);
    questions.flush();
    // Close only the gap left by deletion, preserving the relative order of survivors.
    // Flush in ascending order so every destination is already vacant in MySQL.
    for (Question later : questions.findByQuestionSetIdOrderByOrderNoAsc(setId)) {
      if (later.getOrderNo() > removedOrder) {
        later.reorder(later.getOrderNo() - 1);
        questions.flush();
      }
    }
    set.touch();
  }

  private QuestionSet owned(Long setId, Long userId, QuestionSetErrorCode notFoundCode) {
    QuestionSet set = sets.findLockedById(setId)
        .orElseThrow(() -> new QuestionSetException(notFoundCode));
    if (!set.getHostId().equals(userId)) throw new QuestionSetException(QuestionSetErrorCode.FORBIDDEN);
    return set;
  }

  private Question question(Long setId, Long questionId) {
    return questions.findByIdAndQuestionSetId(questionId, setId)
        .orElseThrow(() -> new QuestionSetException(QuestionSetErrorCode.QUESTION_NOT_FOUND));
  }

  private QuestionResponse replaceChoices(Question q, QuestionWriteRequest request) {
    List<ChoiceResponse> result = new ArrayList<>();
    for (int i = 0; i < request.choices().size(); i++) {
      ChoiceRequest c = request.choices().get(i);
      result.add(ChoiceResponse.from(choices.save(new Choice(q, c.content(), i + 1, c.isCorrect()))));
    }
    choices.flush();
    return QuestionResponse.from(q, result);
  }
}
