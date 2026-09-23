-- 로컬 전용 시드. application-local.yml의 flyway.locations에 db/seed가 있을 때만 실행된다.
-- 여러 번 실행돼도 깨지지 않게 고정 id + INSERT IGNORE를 쓴다.
-- 계정 비밀번호는 모두 sahmhoot1234! (BCrypt)

INSERT IGNORE INTO users (id, email, password_hash, name, role, created_at) VALUES
  (1, 'prof@sahmhoot.test',     '$2a$10$mdBv3NboXjoFTYfE1bDxz.Ghpbn2PMvL2Ibf5YH./BX2/Ek7FDTiK', '김교수',   'PROFESSOR', UTC_TIMESTAMP(6)),
  (2, 'student1@sahmhoot.test', '$2a$10$mdBv3NboXjoFTYfE1bDxz.Ghpbn2PMvL2Ibf5YH./BX2/Ek7FDTiK', '학생하나', 'STUDENT',   UTC_TIMESTAMP(6)),
  (3, 'student2@sahmhoot.test', '$2a$10$mdBv3NboXjoFTYfE1bDxz.Ghpbn2PMvL2Ibf5YH./BX2/Ek7FDTiK', '학생둘',   'STUDENT',   UTC_TIMESTAMP(6)),
  (4, 'student3@sahmhoot.test', '$2a$10$mdBv3NboXjoFTYfE1bDxz.Ghpbn2PMvL2Ibf5YH./BX2/Ek7FDTiK', '학생셋',   'STUDENT',   UTC_TIMESTAMP(6));

INSERT IGNORE INTO question_sets (id, owner_id, title, is_public, time_limit_sec, created_at, updated_at) VALUES
  (1, 1, '샘플: 스택·큐 확인문제', FALSE, 30, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

INSERT IGNORE INTO questions (id, question_set_id, order_no, type, content, created_at) VALUES
  (1, 1, 1, 'MULTIPLE_CHOICE', '스택의 LIFO 특성을 가장 잘 설명한 것은?',               UTC_TIMESTAMP(6)),
  (2, 1, 2, 'OX',              '큐는 먼저 넣은 값이 먼저 나온다.',                      UTC_TIMESTAMP(6)),
  (3, 1, 3, 'MULTIPLE_CHOICE', '배열로 구현한 스택에서 push의 평균 시간 복잡도는?',      UTC_TIMESTAMP(6));

INSERT IGNORE INTO choices (id, question_id, order_no, content, is_correct) VALUES
  (1,  1, 1, '마지막에 넣은 값이 먼저 나온다',   TRUE),
  (2,  1, 2, '먼저 넣은 값이 먼저 나온다',       FALSE),
  (3,  1, 3, '우선순위가 높은 값이 먼저 나온다', FALSE),
  (4,  1, 4, '값이 무작위로 나온다',             FALSE),
  (5,  2, 1, 'O',                                TRUE),
  (6,  2, 2, 'X',                                FALSE),
  (7,  3, 1, 'O(1)',                             TRUE),
  (8,  3, 2, 'O(log n)',                         FALSE),
  (9,  3, 3, 'O(n)',                             FALSE),
  (10, 3, 4, 'O(n²)',                            FALSE);
