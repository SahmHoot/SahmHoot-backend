CREATE TABLE `users`
(
  `id`             bigint PRIMARY KEY AUTO_INCREMENT COMMENT '사용자 ID',
  `email`          varchar(255) UNIQUE NOT NULL COMMENT '이메일',
  `password_hash`  varchar(255)        NOT NULL COMMENT '비밀번호 해시',
  `name`           varchar(50)         NOT NULL COMMENT '실명',
  `student_number` varchar(20) UNIQUE COMMENT '학번, 학생 계정에만 사용',
  `role`           ENUM ('PROFESSOR', 'STUDENT') NOT NULL COMMENT '사용자 역할',
  `email_verified` boolean             NOT NULL DEFAULT false COMMENT '이메일 인증 여부',
  `created_at`     datetime            NOT NULL COMMENT '가입 일시',
  `updated_at`     datetime            NOT NULL COMMENT '수정 일시'
);

CREATE TABLE `question_sets`
(
  `id`          bigint PRIMARY KEY AUTO_INCREMENT COMMENT '문제집 ID',
  `host_id`     bigint       NOT NULL COMMENT '문제집 작성자 ID',
  `title`       varchar(255) NOT NULL COMMENT '문제집 제목',
  `description` text COMMENT '문제집 설명',
  `visibility`  ENUM ('PUBLIC', 'PRIVATE') NOT NULL COMMENT '공개 여부',
  `created_at`  datetime     NOT NULL COMMENT '생성 일시',
  `updated_at`  datetime     NOT NULL COMMENT '수정 일시'
);

CREATE TABLE `questions`
(
  `id`                 bigint PRIMARY KEY AUTO_INCREMENT COMMENT '문항 ID',
  `question_set_id`    bigint   NOT NULL COMMENT '소속 문제집 ID',
  `type`               ENUM ('MULTIPLE_CHOICE', 'TRUE_FALSE') NOT NULL COMMENT '문항 유형',
  `content`            text     NOT NULL COMMENT '문항 내용',
  `time_limit_seconds` int      NOT NULL COMMENT '제한 시간(초)',
  `order_no`           int      NOT NULL COMMENT '문항 순서',
  `created_at`         datetime NOT NULL COMMENT '생성 일시',
  `updated_at`         datetime NOT NULL COMMENT '수정 일시'
);

CREATE TABLE `choices`
(
  `id`          bigint PRIMARY KEY AUTO_INCREMENT COMMENT '선택지 ID',
  `question_id` bigint       NOT NULL COMMENT '소속 문항 ID',
  `content`     varchar(500) NOT NULL COMMENT '선택지 내용',
  `order_no`    int          NOT NULL COMMENT '선택지 순서',
  `is_correct`  boolean      NOT NULL COMMENT '정답 여부'
);

CREATE TABLE `rooms`
(
  `id`         bigint PRIMARY KEY AUTO_INCREMENT COMMENT '수업방 ID',
  `host_id`    bigint            NOT NULL COMMENT '방을 생성한 교수 ID',
  `title`      varchar(50)       NOT NULL COMMENT '수업방 제목',
  `code`       varchar(6) UNIQUE NOT NULL COMMENT '6자리 입장 코드',
  `status`     ENUM ('OPEN', 'PLAYING', 'CLOSED') NOT NULL COMMENT '방 상태',
  `created_at` datetime          NOT NULL COMMENT '방 생성 일시',
  `closed_at`  datetime COMMENT '방 종료 일시'
);

CREATE TABLE `room_participants`
(
  `id`        bigint PRIMARY KEY AUTO_INCREMENT COMMENT '참가자 ID',
  `room_id`   bigint      NOT NULL COMMENT '참가한 방 ID',
  `user_id`   bigint      NOT NULL COMMENT '참가한 학생 계정 ID',
  `nickname`  varchar(50) NOT NULL COMMENT '수업방에서 사용하는 익명 닉네임',
  `joined_at` datetime    NOT NULL COMMENT '입장 일시',
  `left_at`   datetime COMMENT '퇴장 일시'
);

CREATE TABLE `quiz_runs`
(
  `id`              bigint PRIMARY KEY AUTO_INCREMENT COMMENT '퀴즈 실행 ID',
  `room_id`         bigint NOT NULL COMMENT '퀴즈가 진행되는 방 ID',
  `question_set_id` bigint NOT NULL COMMENT '사용한 문제집 ID',
  `status`          ENUM ('READY', 'RUNNING', 'FINISHED', 'ABORTED') NOT NULL COMMENT '퀴즈 상태',
  `started_at`      datetime COMMENT '퀴즈 시작 일시',
  `finished_at`     datetime COMMENT '퀴즈 종료 일시'
);

CREATE TABLE `run_questions`
(
  `id`                 bigint PRIMARY KEY AUTO_INCREMENT COMMENT '실행 문항 ID',
  `quiz_run_id`        bigint NOT NULL COMMENT '소속 퀴즈 실행 ID',
  `type`               ENUM ('MULTIPLE_CHOICE', 'TRUE_FALSE') NOT NULL COMMENT '실행 당시 문항 유형',
  `content`            text   NOT NULL COMMENT '실행 당시 문항 내용 스냅샷',
  `time_limit_seconds` int    NOT NULL COMMENT '실행 당시 제한 시간(초)',
  `order_no`           int    NOT NULL COMMENT '문항 진행 순서',
  `status`             ENUM ('READY', 'OPEN', 'CLOSED') NOT NULL COMMENT '문항 상태',
  `opened_at`          datetime COMMENT '문항 공개 일시',
  `closes_at`          datetime COMMENT '예정 마감 일시',
  `closed_at`          datetime COMMENT '실제 마감 일시'
);

CREATE TABLE `run_choices`
(
  `id`              bigint PRIMARY KEY AUTO_INCREMENT COMMENT '실행 선택지 ID',
  `run_question_id` bigint       NOT NULL COMMENT '소속 실행 문항 ID',
  `content`         varchar(500) NOT NULL COMMENT '실행 당시 선택지 내용',
  `order_no`        int          NOT NULL COMMENT '선택지 순서',
  `is_correct`      boolean      NOT NULL COMMENT '실행 당시 정답 여부'
);

CREATE TABLE `answers`
(
  `id`              bigint PRIMARY KEY AUTO_INCREMENT COMMENT '답변 ID',
  `run_question_id` bigint   NOT NULL COMMENT '답변한 실행 문항 ID',
  `participant_id`  bigint   NOT NULL COMMENT '답변한 참가자 ID',
  `run_choice_id`   bigint   NOT NULL COMMENT '선택한 실행 선택지 ID',
  `submitted_at`    datetime NOT NULL COMMENT '최초 제출 일시',
  `updated_at`      datetime NOT NULL COMMENT '마지막 답변 변경 일시'
);

CREATE UNIQUE INDEX `questions_index_0` ON `questions` (`question_set_id`, `order_no`);

CREATE UNIQUE INDEX `choices_index_1` ON `choices` (`question_id`, `order_no`);

CREATE UNIQUE INDEX `room_participants_index_2` ON `room_participants` (`room_id`, `user_id`);

CREATE UNIQUE INDEX `room_participants_index_3` ON `room_participants` (`room_id`, `nickname`);

CREATE UNIQUE INDEX `run_questions_index_4` ON `run_questions` (`quiz_run_id`, `order_no`);

CREATE UNIQUE INDEX `run_choices_index_5` ON `run_choices` (`run_question_id`, `order_no`);

CREATE UNIQUE INDEX `run_choices_index_6` ON `run_choices` (`run_question_id`, `id`);

CREATE UNIQUE INDEX `answers_index_7` ON `answers` (`run_question_id`, `participant_id`);

ALTER TABLE `users` COMMENT = '교수 및 학생 사용자 계정';

ALTER TABLE `question_sets` COMMENT = '호스트가 생성한 문제집';

ALTER TABLE `questions` COMMENT = '문제집에 포함되는 원본 문항';

ALTER TABLE `choices` COMMENT = '원본 문항의 선택지';

ALTER TABLE `rooms` COMMENT = '실시간 퀴즈가 진행되는 수업방';

ALTER TABLE `room_participants` COMMENT = '학생 계정과 수업방별 익명 닉네임을 매핑하는 참가 정보';

ALTER TABLE `quiz_runs` COMMENT = '특정 수업방에서 실행된 하나의 퀴즈';

ALTER TABLE `run_questions` COMMENT = '퀴즈 시작 시 원본 문항을 복사한 실행용 스냅샷';

ALTER TABLE `run_choices` COMMENT = '퀴즈 시작 시 원본 선택지를 복사한 실행용 스냅샷';

ALTER TABLE `answers` COMMENT = '참가자가 실행 문항에 제출한 최종 답변';

ALTER TABLE `question_sets`
  ADD FOREIGN KEY (`host_id`) REFERENCES `users` (`id`);

ALTER TABLE `questions`
  ADD FOREIGN KEY (`question_set_id`) REFERENCES `question_sets` (`id`);

ALTER TABLE `choices`
  ADD FOREIGN KEY (`question_id`) REFERENCES `questions` (`id`);

ALTER TABLE `rooms`
  ADD FOREIGN KEY (`host_id`) REFERENCES `users` (`id`);

ALTER TABLE `room_participants`
  ADD FOREIGN KEY (`room_id`) REFERENCES `rooms` (`id`);

ALTER TABLE `room_participants`
  ADD FOREIGN KEY (`user_id`) REFERENCES `users` (`id`);

ALTER TABLE `quiz_runs`
  ADD FOREIGN KEY (`room_id`) REFERENCES `rooms` (`id`);

ALTER TABLE `quiz_runs`
  ADD FOREIGN KEY (`question_set_id`) REFERENCES `question_sets` (`id`);

ALTER TABLE `run_questions`
  ADD FOREIGN KEY (`quiz_run_id`) REFERENCES `quiz_runs` (`id`);

ALTER TABLE `run_choices`
  ADD FOREIGN KEY (`run_question_id`) REFERENCES `run_questions` (`id`);

ALTER TABLE `answers`
  ADD FOREIGN KEY (`run_question_id`) REFERENCES `run_questions` (`id`);

ALTER TABLE `answers`
  ADD FOREIGN KEY (`participant_id`) REFERENCES `room_participants` (`id`);

ALTER TABLE `answers`
  ADD FOREIGN KEY (`run_question_id`, `run_choice_id`) REFERENCES `run_choices` (`run_question_id`, `id`);
