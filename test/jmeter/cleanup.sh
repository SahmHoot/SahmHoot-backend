#!/usr/bin/env bash
# 부하 테스트가 만든 방·문제 세트와 그 기록을 지우는 SQL을 출력한다.
#
#   ./cleanup.sh runs.csv > cleanup.sql
#
# runs.csv는 JMeter 정리 단계가 실행마다 "방 ID,문제 세트 ID"를 한 줄씩 쌓는 파일이다(-JrunsFile).
# 이 목록에 있으면서 @load.test 계정이 만든 것만 지운다. 방은 종료(CLOSED)된 것만 지운다.
# 수업 기록 보존 규칙의 예외로, 부하 테스트 데이터에만 쓴다.
#
# 한 트랜잭션으로 실행한다. 다른 방에서 쓴 문제 세트처럼 외래 키에 걸리면 mysql이 오류로 멈추고 커밋하지 않는다.
# 테이블이 늘면(채팅 저장 등) 여기에 함께 추가한다.
set -euo pipefail

file="${1:?사용법: $0 <runs.csv>}"
rooms=()
sets=()
while IFS=, read -r room set; do
  [[ -z "$room" ]] && continue
  [[ "$room" =~ ^[0-9]+$ && "$set" =~ ^[0-9]+$ ]] || { echo "잘못된 줄: ${room},${set}" >&2; exit 1; }
  rooms+=("$room")
  sets+=("$set")
done < "$file"
[[ ${#rooms[@]} -gt 0 ]] || { echo "지울 실행이 없습니다" >&2; exit 1; }
room_list=$(IFS=,; echo "${rooms[*]}")
set_list=$(IFS=,; echo "${sets[*]}")

cat <<SQL
START TRANSACTION;
CREATE TEMPORARY TABLE load_rooms AS
  SELECT r.id FROM rooms r JOIN users u ON u.id = r.host_id
  WHERE r.id IN (${room_list}) AND r.status = 'CLOSED' AND u.email LIKE '%@load.test';
CREATE TEMPORARY TABLE load_sets AS
  SELECT s.id FROM question_sets s JOIN users u ON u.id = s.host_id
  WHERE s.id IN (${set_list}) AND u.email LIKE '%@load.test';
SELECT (SELECT COUNT(*) FROM load_rooms) AS rooms_to_delete, (SELECT COUNT(*) FROM load_sets) AS sets_to_delete;

DELETE a FROM answers a
  JOIN run_questions rq ON rq.id = a.run_question_id
  JOIN quiz_runs qr ON qr.id = rq.quiz_run_id
  WHERE qr.room_id IN (SELECT id FROM load_rooms);
DELETE rc FROM run_choices rc
  JOIN run_questions rq ON rq.id = rc.run_question_id
  JOIN quiz_runs qr ON qr.id = rq.quiz_run_id
  WHERE qr.room_id IN (SELECT id FROM load_rooms);
DELETE rq FROM run_questions rq
  JOIN quiz_runs qr ON qr.id = rq.quiz_run_id
  WHERE qr.room_id IN (SELECT id FROM load_rooms);
DELETE FROM quiz_runs WHERE room_id IN (SELECT id FROM load_rooms);
DELETE FROM room_participants WHERE room_id IN (SELECT id FROM load_rooms);
DELETE FROM rooms WHERE id IN (SELECT id FROM load_rooms);

DELETE c FROM choices c
  JOIN questions q ON q.id = c.question_id
  WHERE q.question_set_id IN (SELECT id FROM load_sets);
DELETE FROM questions WHERE question_set_id IN (SELECT id FROM load_sets);
DELETE FROM question_sets WHERE id IN (SELECT id FROM load_sets);
COMMIT;
SQL
