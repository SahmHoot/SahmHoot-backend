package com.sahmhoot.seed;

import com.sahmhoot.questionset.entity.Choice;
import com.sahmhoot.questionset.entity.Question;
import com.sahmhoot.questionset.entity.QuestionSet;
import com.sahmhoot.questionset.entity.QuestionType;
import com.sahmhoot.questionset.entity.Visibility;
import com.sahmhoot.questionset.repository.ChoiceRepository;
import com.sahmhoot.questionset.repository.QuestionRepository;
import com.sahmhoot.questionset.repository.QuestionSetRepository;
import com.sahmhoot.user.Role;
import com.sahmhoot.user.User;
import com.sahmhoot.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 로컬 개발용 샘플. 기존 계정과 문제집은 변경하지 않는다. */
@Component
@Profile("local & !test & !prod")
@RequiredArgsConstructor
public class LocalSeedRunner implements ApplicationRunner {
  static final String TITLE = "[로컬 샘플] 컴퓨터 기초 퀴즈";
  private static final String PASSWORD = "Test1234!";

  private final UserRepository users;
  private final QuestionSetRepository sets;
  private final QuestionRepository questions;
  private final ChoiceRepository choices;
  private final PasswordEncoder passwordEncoder;
  private final EntityManager entityManager;

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public void run(ApplicationArguments args) {
    User professor = account("professor@local.test", "로컬 교수", Role.PROFESSOR);
    // 같은 교수에 대한 시드 실행을 직렬화해 세트 조회와 생성을 함께 보호한다.
    entityManager.lock(professor, LockModeType.PESSIMISTIC_WRITE);
    for (int i = 1; i <= 3; i++) {
      account("student" + i + "@local.test", "로컬 학생 " + i, Role.STUDENT);
    }
    if (sets.existsByHostIdAndTitle(professor.getId(), TITLE)) return;

    QuestionSet set = sets.save(new QuestionSet(professor.getId(), TITLE,
        "객관식과 OX 문항을 체험하는 개발 전용 문제집입니다.", Visibility.PUBLIC));
    Question multipleChoice = questions.save(new Question(set, QuestionType.MULTIPLE_CHOICE,
        "다음 중 FIFO 방식의 자료구조는 무엇인가요?", 60, 1));
    choices.saveAll(List.of(
        new Choice(multipleChoice, "스택", 1, false),
        new Choice(multipleChoice, "큐", 2, true),
        new Choice(multipleChoice, "트리", 3, false),
        new Choice(multipleChoice, "그래프", 4, false)));
    Question trueFalse = questions.save(new Question(set, QuestionType.TRUE_FALSE,
        "스택은 LIFO 방식의 자료구조이다.", 30, 2));
    choices.saveAll(List.of(
        new Choice(trueFalse, "O", 1, true),
        new Choice(trueFalse, "X", 2, false)));
  }

  private User account(String email, String name, Role role) {
    User user = users.findByEmail(email).orElseGet(() -> users.saveAndFlush(
        User.create(email, passwordEncoder.encode(PASSWORD), name, null, role)));
    if (user.getRole() != role) {
      throw new IllegalStateException("로컬 시드 계정의 기존 역할이 일치하지 않습니다: " + email);
    }
    return user;
  }
}
