-- 팀원 검수용 미확정 초안. 모든 일시는 UTC로 저장한다.
CREATE TABLE `chat_messages`
(
  `id`                    bigint PRIMARY KEY AUTO_INCREMENT COMMENT '채팅 메시지 ID',
  `room_id`               bigint       NOT NULL COMMENT '소속 수업방 ID',
  `sender_user_id`        bigint       NOT NULL COMMENT '발신자 계정 ID',
  `sender_participant_id` bigint COMMENT '학생 참가자 ID, 교수 발신 시 NULL',
  `content`               varchar(300) NOT NULL COMMENT '메시지 내용',
  `parent_id`             bigint COMMENT '답글 원본 메시지 ID',
  `created_at`            datetime(6)  NOT NULL COMMENT '전송 일시',
  `deleted_at`            datetime(6) COMMENT '소프트 삭제 일시'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE `chat_pins`
(
  `room_id`           bigint      NOT NULL COMMENT '소속 수업방 ID',
  `message_id`        bigint      NOT NULL COMMENT '고정 메시지 ID',
  `pinned_by_user_id` bigint      NOT NULL COMMENT '고정한 교수 계정 ID',
  `pinned_at`         datetime(6) NOT NULL COMMENT '고정 일시',
  PRIMARY KEY (`room_id`, `message_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE `chat_restrictions`
(
  `room_id`               bigint      NOT NULL COMMENT '소속 수업방 ID',
  `participant_id`        bigint      NOT NULL COMMENT '제한 대상 참가자 ID',
  `restricted_by_user_id` bigint      NOT NULL COMMENT '제한한 교수 계정 ID',
  `restricted_at`         datetime(6) NOT NULL COMMENT '제한 시작 일시',
  PRIMARY KEY (`room_id`, `participant_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX `idx_rooms_chat_retention` ON `rooms` (`status`, `closed_at`, `id`);

-- V1 인덱스로 대체할 수 없는 참가자 FK의 고유 참조 키다.
CREATE UNIQUE INDEX `uq_room_participants_chat_room_id` ON `room_participants` (`room_id`, `id`);

CREATE UNIQUE INDEX `uq_room_participants_chat_sender` ON `room_participants` (`room_id`, `id`, `user_id`);

CREATE UNIQUE INDEX `uq_chat_message_room_id` ON `chat_messages` (`room_id`, `id`);

CREATE INDEX `idx_chat_messages_recent` ON `chat_messages` (`room_id`, `created_at`, `id`);

CREATE INDEX `idx_chat_messages_parent` ON `chat_messages` (`room_id`, `parent_id`);

ALTER TABLE `chat_messages` COMMENT = '수업방 채팅 메시지';

ALTER TABLE `chat_pins` COMMENT = '수업방별 현재 고정 메시지';

ALTER TABLE `chat_restrictions` COMMENT = '수업방 참가자의 현재 채팅 제한';

ALTER TABLE `chat_messages`
  ADD CONSTRAINT `ck_chat_message_content` CHECK (CHAR_LENGTH(`content`) BETWEEN 1 AND 300);

ALTER TABLE `chat_messages`
  ADD CONSTRAINT `fk_chat_message_room` FOREIGN KEY (`room_id`) REFERENCES `rooms` (`id`);

ALTER TABLE `chat_messages`
  ADD CONSTRAINT `fk_chat_message_sender` FOREIGN KEY (`sender_user_id`) REFERENCES `users` (`id`);

ALTER TABLE `chat_messages`
  ADD CONSTRAINT `fk_chat_message_participant` FOREIGN KEY (`room_id`, `sender_participant_id`, `sender_user_id`)
    REFERENCES `room_participants` (`room_id`, `id`, `user_id`);

ALTER TABLE `chat_messages`
  ADD CONSTRAINT `fk_chat_message_parent` FOREIGN KEY (`room_id`, `parent_id`)
    REFERENCES `chat_messages` (`room_id`, `id`);

ALTER TABLE `chat_pins`
  ADD CONSTRAINT `fk_chat_pin_message` FOREIGN KEY (`room_id`, `message_id`)
    REFERENCES `chat_messages` (`room_id`, `id`) ON DELETE CASCADE;

ALTER TABLE `chat_pins`
  ADD CONSTRAINT `fk_chat_pin_actor` FOREIGN KEY (`pinned_by_user_id`) REFERENCES `users` (`id`);

ALTER TABLE `chat_restrictions`
  ADD CONSTRAINT `fk_chat_restriction_participant` FOREIGN KEY (`room_id`, `participant_id`)
    REFERENCES `room_participants` (`room_id`, `id`);

ALTER TABLE `chat_restrictions`
  ADD CONSTRAINT `fk_chat_restriction_actor` FOREIGN KEY (`restricted_by_user_id`) REFERENCES `users` (`id`);

-- 교수 전용 답글·이메일 조회·종료 후 기록 조회와 제한·고정 권한은 백엔드에서 검증한다.
-- 작성자 역할은 users.role, 학생 닉네임은 room_participants.nickname, 교수 표시 이름은 users.name으로 조회한다.
-- 학생의 수업 중 닉네임 변경 금지, sender_participant_id 필수 및 계정·참가자 일치는 백엔드에서 검증한다.
-- 소프트 삭제 시 본문 마스킹과 같은 트랜잭션의 고정 해제는 후속 서비스에서 처리한다.
-- 제한·고정 해제는 해당 행을 삭제하며 이력을 남기지 않는다. 고정 개수는 제한하지 않는다.
-- 재접속 시 최근 50건은 created_at DESC, id DESC로 조회하고 고정은 개수 제한 없이 별도 조회한다.
-- 종료 후 조회 UI와 자동 삭제 스케줄러 구현은 보류한다.

-- 7일 보관 정리 대상: status = 'CLOSED' AND closed_at <= UTC_TIMESTAMP(6) - INTERVAL 7 DAY.
-- 대상 방별로 parent_id를 배치로 해제한 뒤 메시지를 배치 삭제한다. 고정은 연쇄 삭제된다.
-- 메시지 유무와 무관하게 같은 방의 제한도 배치 삭제하며 rooms와 room_participants는 유지한다.
