-- 04 ERD v0.6 · 테이블 11개 · 모든 시각은 UTC
CREATE TABLE users (
  id BIGINT NOT NULL AUTO_INCREMENT,
  email VARCHAR(255) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  name VARCHAR(20) NOT NULL,
  role VARCHAR(20) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE email_verifications (
  id BIGINT NOT NULL AUTO_INCREMENT,
  email VARCHAR(255) NOT NULL,
  code CHAR(6) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  verified_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_email_verifications_email_created (email, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE question_sets (
  id BIGINT NOT NULL AUTO_INCREMENT,
  owner_id BIGINT NOT NULL,
  title VARCHAR(50) NOT NULL,
  is_public BOOLEAN NOT NULL DEFAULT FALSE,
  time_limit_sec INT NOT NULL DEFAULT 60,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_question_sets_owner (owner_id),
  CONSTRAINT fk_question_sets_owner FOREIGN KEY (owner_id) REFERENCES users (id),
  CONSTRAINT ck_question_sets_time_limit CHECK (time_limit_sec BETWEEN 10 AND 300)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE questions (
  id BIGINT NOT NULL AUTO_INCREMENT,
  question_set_id BIGINT NOT NULL,
  order_no INT NOT NULL,
  type VARCHAR(20) NOT NULL,
  content VARCHAR(500) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_questions_set_order (question_set_id, order_no),
  CONSTRAINT fk_questions_set FOREIGN KEY (question_set_id) REFERENCES question_sets (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE choices (
  id BIGINT NOT NULL AUTO_INCREMENT,
  question_id BIGINT NOT NULL,
  order_no INT NOT NULL,
  content VARCHAR(200) NOT NULL,
  is_correct BOOLEAN NOT NULL,
  PRIMARY KEY (id),
  KEY idx_choices_question_order (question_id, order_no),
  CONSTRAINT fk_choices_question FOREIGN KEY (question_id) REFERENCES questions (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE rooms (
  id BIGINT NOT NULL AUTO_INCREMENT,
  owner_id BIGINT NOT NULL,
  title VARCHAR(50) NOT NULL,
  join_code CHAR(6) NOT NULL,
  status VARCHAR(20) NOT NULL,
  current_run_id BIGINT NULL,
  pinned_message_id BIGINT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_rooms_join_code (join_code),
  KEY idx_rooms_owner (owner_id),
  CONSTRAINT fk_rooms_owner FOREIGN KEY (owner_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 소유자는 닉네임 = users.name(최대 20자)으로 자동 등록되므로 20자
CREATE TABLE room_participants (
  id BIGINT NOT NULL AUTO_INCREMENT,
  room_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  nickname VARCHAR(20) NOT NULL,
  chat_blocked BOOLEAN NOT NULL DEFAULT FALSE,
  joined_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_room_participants_room_user (room_id, user_id),
  CONSTRAINT fk_room_participants_room FOREIGN KEY (room_id) REFERENCES rooms (id) ON DELETE CASCADE,
  CONSTRAINT fk_room_participants_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE quiz_runs (
  id BIGINT NOT NULL AUTO_INCREMENT,
  room_id BIGINT NOT NULL,
  question_set_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL,
  time_limit_sec INT NOT NULL,
  total_questions INT NOT NULL,
  started_at DATETIME(6) NOT NULL,
  finished_at DATETIME(6) NULL,
  PRIMARY KEY (id),
  KEY idx_quiz_runs_room (room_id),
  CONSTRAINT fk_quiz_runs_room FOREIGN KEY (room_id) REFERENCES rooms (id) ON DELETE CASCADE,
  CONSTRAINT fk_quiz_runs_set FOREIGN KEY (question_set_id) REFERENCES question_sets (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE run_questions (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_id BIGINT NOT NULL,
  question_id BIGINT NOT NULL,
  order_no INT NOT NULL,
  opened_at DATETIME(6) NOT NULL,
  closes_at DATETIME(6) NOT NULL,
  closed_at DATETIME(6) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_run_questions_run_order (run_id, order_no),
  UNIQUE KEY uk_run_questions_run_question (run_id, question_id),
  CONSTRAINT fk_run_questions_run FOREIGN KEY (run_id) REFERENCES quiz_runs (id) ON DELETE CASCADE,
  CONSTRAINT fk_run_questions_question FOREIGN KEY (question_id) REFERENCES questions (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE answers (
  id BIGINT NOT NULL AUTO_INCREMENT,
  run_question_id BIGINT NOT NULL,
  participant_id BIGINT NOT NULL,
  choice_id BIGINT NOT NULL,
  answered_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_answers_run_question_participant (run_question_id, participant_id),
  CONSTRAINT fk_answers_run_question FOREIGN KEY (run_question_id) REFERENCES run_questions (id) ON DELETE CASCADE,
  CONSTRAINT fk_answers_participant FOREIGN KEY (participant_id) REFERENCES room_participants (id) ON DELETE CASCADE,
  CONSTRAINT fk_answers_choice FOREIGN KEY (choice_id) REFERENCES choices (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 메시지는 앱에서 소프트 삭제(deleted_at)만 하고, 실제 삭제는 방 삭제 때 연쇄로만 일어남
CREATE TABLE chat_messages (
  id BIGINT NOT NULL AUTO_INCREMENT,
  room_id BIGINT NOT NULL,
  sender_id BIGINT NOT NULL,
  content VARCHAR(300) NOT NULL,
  parent_id BIGINT NULL,
  created_at DATETIME(6) NOT NULL,
  deleted_at DATETIME(6) NULL,
  PRIMARY KEY (id),
  KEY idx_chat_messages_room_created (room_id, created_at),
  CONSTRAINT fk_chat_messages_room FOREIGN KEY (room_id) REFERENCES rooms (id) ON DELETE CASCADE,
  CONSTRAINT fk_chat_messages_sender FOREIGN KEY (sender_id) REFERENCES room_participants (id) ON DELETE CASCADE,
  CONSTRAINT fk_chat_messages_parent FOREIGN KEY (parent_id) REFERENCES chat_messages (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 순환 참조 FK는 마지막에 추가. 방·회차 삭제 전에 앱이 이 두 컬럼을 NULL로 비운다(04 ERD)
ALTER TABLE rooms
  ADD CONSTRAINT fk_rooms_current_run FOREIGN KEY (current_run_id) REFERENCES quiz_runs (id),
  ADD CONSTRAINT fk_rooms_pinned_message FOREIGN KEY (pinned_message_id) REFERENCES chat_messages (id);
