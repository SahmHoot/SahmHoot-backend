package com.sahmhoot.questionset.service;

import com.sahmhoot.questionset.entity.Choice;
import com.sahmhoot.questionset.entity.Question;
import com.sahmhoot.questionset.repository.ChoiceRepository;
import com.sahmhoot.questionset.repository.QuestionRepository;
import com.sahmhoot.questionset.repository.QuestionSetRepository;
import com.sahmhoot.quiz.service.port.QuestionSetPort;
import com.sahmhoot.quiz.service.port.QuestionSetSnapshot;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 퀴즈가 정한 {@link QuestionSetPort}의 실제 구현.
 *
 * <p>퀴즈 서비스가 {@code questionSetPort.findQuestionSet(..)}을 부르면 스프링이 이 클래스의 메서드를 실행한다.
 * 세트·문항·선택지 원본은 세트 모듈의 리포지토리(JPA)로 읽는다. 퀴즈 패키지에서는 포트
 * 인터페이스와 데이터 상자(record)만 가져다 쓰고, 퀴즈 오류·엔티티는 쓰지 않는다.
 *
 * <p>회차 테이블(run_questions, run_choices) 저장은 #15 계약에 따라 이 클래스가 맡는다.
 * 퀴즈 엔티티를 가져다 쓰지 않으려고 이 저장만 SQL로 한다.
 */
@Service
@RequiredArgsConstructor
public class QuizQuestionSetAdapter implements QuestionSetPort {
  private static final String INSERT_RUN_QUESTION_SQL =
      "INSERT INTO run_questions (quiz_run_id, type, content, time_limit_seconds, order_no, status) "
          + "VALUES (?, ?, ?, ?, ?, 'READY')";
  private static final String INSERT_RUN_CHOICE_SQL =
      "INSERT INTO run_choices (run_question_id, content, order_no, is_correct) VALUES (?, ?, ?, ?)";

  private final QuestionSetRepository sets;
  private final QuestionRepository questions;
  private final ChoiceRepository choices;
  private final JdbcTemplate jdbc;

  /** 세트를 찾아 퀴즈에 필요한 값(ID, 주인, 제목)만 담아 돌려준다. 세트 엔티티는 넘기지 않는다. */
  @Override
  public Optional<QuestionSetSnapshot> findQuestionSet(Long questionSetId) {
    return sets.findById(questionSetId)
        .map(set -> new QuestionSetSnapshot(set.getId(), set.getHostId(), set.getTitle()));
  }

  /**
   * 원본을 순서대로 읽어 회차 문항·선택지로 복사한다. 퀴즈 시작 시점의 내용을 그대로 떠 두는 것이라,
   * 이후 원본을 고쳐도 진행 중인 회차는 바뀌지 않는다.
   * MANDATORY: 퀴즈 시작 트랜잭션 안에서만 실행되므로, 시작이 실패하면 복사도 함께 취소된다.
   */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public int copyQuestionsToQuizRun(Long questionSetId, Long quizRunId) {
    List<Question> originals = questions.findByQuestionSetIdOrderByOrderNoAsc(questionSetId);
    if (originals.isEmpty()) return 0;
    // 선택지는 한 번에 읽어 문항별로 나눠 둔다(문항마다 따로 조회하지 않도록).
    Map<Long, List<Choice>> choicesByQuestion = choices
        .findByQuestionIdInOrderByOrderNoAsc(originals.stream().map(Question::getId).toList())
        .stream()
        .collect(Collectors.groupingBy(choice -> choice.getQuestion().getId()));
    for (Question original : originals) {
      Long runQuestionId = insertRunQuestion(quizRunId, original);
      for (Choice choice : choicesByQuestion.getOrDefault(original.getId(), List.of())) {
        jdbc.update(INSERT_RUN_CHOICE_SQL, runQuestionId, choice.getContent(), choice.getOrderNo(),
            choice.isCorrect());
      }
    }
    return originals.size();
  }

  /** 회차 문항 한 줄을 넣고, 선택지를 연결할 새 ID를 돌려준다. */
  private Long insertRunQuestion(Long quizRunId, Question original) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbc.update(connection -> {
      PreparedStatement statement =
          connection.prepareStatement(INSERT_RUN_QUESTION_SQL, Statement.RETURN_GENERATED_KEYS);
      statement.setLong(1, quizRunId);
      statement.setString(2, original.getType().name());
      statement.setString(3, original.getContent());
      statement.setInt(4, original.getTimeLimitSeconds());
      statement.setInt(5, original.getOrderNo());
      return statement;
    }, keyHolder);
    Number generatedKey = keyHolder.getKey();
    if (generatedKey == null) throw new IllegalStateException("run_questions insert did not return a generated id");
    return generatedKey.longValue();
  }
}
