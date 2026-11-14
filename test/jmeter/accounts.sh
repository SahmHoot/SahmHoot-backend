#!/usr/bin/env bash
# 부하 테스트용 계정 N개를 만드는 SQL을 출력한다(가입에는 메일 인증이 필요해 DB에 바로 넣는다).
# 비밀번호는 저장소에 남기지 않도록 환경 변수로 받고, htpasswd로 BCrypt 해시를 만든다.
#
#   LOAD_PASSWORD='...' ./accounts.sh 30 > accounts.sql                              학생 load1~30@load.test
#   LOAD_PASSWORD="$PROFESSOR_PASSWORD" ./accounts.sh 1 loadprof PROFESSOR > prof.sql  부하 전용 교수 loadprof1@load.test
#
# 이메일은 {접두사}{번호}@load.test, 1번부터 N번까지. 학생 번호는 JMeter 스레드 번호와 같다.
# 이미 있는 계정은 비밀번호와 역할만 바꾼다(실행 뒤 무작위 비밀번호로 다시 넣으면 그 계정으로 로그인할 수 없다).
# 시각은 UTC로 넣는다(앱은 모든 시각을 UTC로 저장한다).
set -euo pipefail

count="${1:?사용법: LOAD_PASSWORD=... $0 <계정 수> [접두사] [STUDENT|PROFESSOR]}"
prefix="${2:-load}"
role="${3:-STUDENT}"
: "${LOAD_PASSWORD:?LOAD_PASSWORD 환경 변수가 필요합니다}"
[[ "$count" =~ ^[1-9][0-9]*$ ]] || { echo "계정 수는 1 이상의 정수여야 합니다" >&2; exit 1; }
[[ "$prefix" =~ ^[a-z][a-z0-9]*$ ]] || { echo "접두사는 영문 소문자로 시작하는 영문 소문자·숫자여야 합니다" >&2; exit 1; }
[[ "$role" == STUDENT || "$role" == PROFESSOR ]] || { echo "역할은 STUDENT 또는 PROFESSOR입니다" >&2; exit 1; }
command -v htpasswd >/dev/null || { echo "htpasswd가 필요합니다(apache2-utils / macOS 기본 포함)" >&2; exit 1; }

# 비밀번호를 명령 인자로 넘기면 실행 중에 ps로 보이므로 표준 입력으로 넘긴다.
hash="$(printf '%s' "$LOAD_PASSWORD" | htpasswd -inBC 10 "" | tr -d ':\n')"
label=$([ "$role" == PROFESSOR ] && echo "부하교수" || echo "부하학생")

echo "INSERT INTO users (email, password_hash, name, role, email_verified, created_at, updated_at) VALUES"
for ((i = 1; i <= count; i++)); do
  sep=$([ "$i" -lt "$count" ] && echo "," || echo "")
  echo "  ('${prefix}${i}@load.test', '${hash}', '${label}${i}', '${role}', true, UTC_TIMESTAMP(), UTC_TIMESTAMP())${sep}"
done
echo "ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash), role = VALUES(role), updated_at = UTC_TIMESTAMP();"
