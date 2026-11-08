#!/usr/bin/env bash
# 부하 테스트용 학생 계정 N개를 만드는 SQL을 출력한다(가입에는 메일 인증이 필요해 DB에 바로 넣는다).
# 비밀번호는 저장소에 남기지 않도록 환경 변수로 받고, htpasswd로 BCrypt 해시를 만든다.
#
#   LOAD_PASSWORD='...' ./accounts.sh 30 > accounts.sql
#   mysql ... sahmhoot < accounts.sql
#
# 이메일은 {접두사}{번호}@load.test(기본 접두사 load), 1번부터 N번까지. JMeter 스레드 번호와 같다.
# 이미 있는 계정은 비밀번호만 바꾼다. 실제 사용자 계정이 있는 DB에는 넣지 않는다.
set -euo pipefail

count="${1:?사용법: LOAD_PASSWORD=... $0 <계정 수> [접두사]}"
prefix="${2:-load}"
: "${LOAD_PASSWORD:?LOAD_PASSWORD 환경 변수가 필요합니다}"
command -v htpasswd >/dev/null || { echo "htpasswd가 필요합니다(apache2-utils / macOS 기본 포함)" >&2; exit 1; }

hash="$(htpasswd -bnBC 10 "" "$LOAD_PASSWORD" | tr -d ':\n')"

echo "INSERT INTO users (email, password_hash, name, role, email_verified, created_at, updated_at) VALUES"
for ((i = 1; i <= count; i++)); do
  sep=$([ "$i" -lt "$count" ] && echo "," || echo "")
  echo "  ('${prefix}${i}@load.test', '${hash}', '부하학생${i}', 'STUDENT', true, NOW(), NOW())${sep}"
done
echo "ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash);"
