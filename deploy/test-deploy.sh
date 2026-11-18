#!/usr/bin/env bash
# deploy.sh 시험(#20). 서버 없이 임시 폴더에 가짜 서버를 만들어 실행한다.
#   bash deploy/test-deploy.sh
# sudo·systemctl·mysqldump·curl·npm·flock·sleep은 가짜 명령으로 바꾸고, git은 진짜를 쓴다.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
SECRET="s3cret-value-must-not-leak"
FAILS=0

pass() { printf '  통과: %s\n' "$1"; }
fail() { printf '  실패: %s\n' "$1"; FAILS=$((FAILS + 1)); }
check() { if eval "$2"; then pass "$1"; else fail "$1"; fi; }

# --- 가짜 명령 ---
mkdir -p "$T/bin"
stub() { printf '#!/bin/sh\n%s\n' "$2" > "$T/bin/$1"; chmod +x "$T/bin/$1"; }
# CP_FAIL: 새 jar 준비 복사가 중간에 끊긴다(대상이 0바이트로 남고 실패). 디스크 부족 흉내.
stub sudo 'if [ -n "${CP_FAIL:-}" ] && [ "$1" = cp ] && [ "${3##*/}" = app.jar.new ]; then : > "$3"; exit 1; fi
exec "$@"'
# START_FAIL_JAR: app.jar 내용이 이 값이면 start가 실패한다.
stub systemctl 'echo "$*" >> "$STUB_LOG"
jar="$SAHMHOOT_DEPLOY_PREFIX/opt/sahmhoot/app.jar"
if [ "$1" = start ] && [ -n "${START_FAIL_JAR:-}" ] && [ "$(cat "$jar")" = "$START_FAIL_JAR" ]; then exit 1; fi
exit 0'
stub journalctl 'echo "(가짜 로그)"'
stub mysqldump 'echo "-- fake dump"'
stub flock 'exit 0'
stub sleep 'exit 0'
stub curl 'jar="$SAHMHOOT_DEPLOY_PREFIX/opt/sahmhoot/app.jar"
[ -n "${FAIL_JAR:-}" ] && [ "$(cat "$jar")" = "$FAIL_JAR" ] && exit 7
echo "{\"status\":\"UP\"}"'
stub npm '[ -n "${NPM_FAIL:-}" ] && exit 1
if [ "$1" = run ]; then mkdir -p dist && cp version.txt dist/index.html; fi'
export PATH="$T/bin:$PATH" STUB_LOG="$T/systemctl.log" SAHMHOOT_DEPLOY_PREFIX="$T/root"
export GIT_AUTHOR_NAME=test GIT_AUTHOR_EMAIL=test@example.invalid
export GIT_COMMITTER_NAME=test GIT_COMMITTER_EMAIL=test@example.invalid

# --- 가짜 원격 저장소와 서버 소스 폴더 ---
make_origin() { # $1 이름
  git init -q --bare -b develop "$T/origin/$1.git"
  git clone -q "$T/origin/$1.git" "$T/work/$1" 2>/dev/null
  git -C "$T/work/$1" checkout -q -b develop
}
make_origin backend
mkdir -p "$T/work/backend/deploy"
cp "$HERE/deploy.sh" "$T/work/backend/deploy/deploy.sh"
printf '#!/bin/sh\nrm -rf build && mkdir -p build/libs\ncp version.txt build/libs/sahmhoot-0.0.1-SNAPSHOT.jar\necho plain > build/libs/sahmhoot-0.0.1-SNAPSHOT-plain.jar\n' \
  > "$T/work/backend/gradlew"
chmod +x "$T/work/backend/gradlew"
echo build/ > "$T/work/backend/.gitignore"
make_origin frontend
printf 'dist/\nnode_modules/\n' > "$T/work/frontend/.gitignore"
publish() { # $1 저장소 $2 버전
  echo "$2" > "$T/work/$1/version.txt"
  git -C "$T/work/$1" add -A && git -C "$T/work/$1" commit -q -m "$2"
  git -C "$T/work/$1" push -q origin develop
}
publish backend v1
publish frontend f1
mkdir -p "$T/srv"
git clone -q -b develop "$T/origin/backend.git" "$T/srv/backend"
git clone -q -b develop "$T/origin/frontend.git" "$T/srv/frontend"
DEPLOY="$T/srv/backend/deploy/deploy.sh"

# --- 가짜 서버 파일 ---
R="$T/root"
mkdir -p "$R/etc" "$R/opt/sahmhoot" "$R/var/www/sahmhoot"
write_env() {
  {
    echo "SPRING_PROFILES_ACTIVE=prod"
    echo "DB_USERNAME=sahmhoot_app"
    echo "DB_PASSWORD=$SECRET"
    echo "JWT_SECRET=$SECRET"
    echo "CORS_ALLOWED_ORIGINS=https://sahmhoot.com"
    echo "APP_ROOM_JOIN_BASE_URL=https://sahmhoot.com"
  } > "$R/etc/sahmhoot.env"
}
write_env
echo old > "$R/opt/sahmhoot/app.jar"

run() { # 출력을 $T/out에 남기고 종료 코드를 돌려준다
  set +e
  bash "$DEPLOY" "$@" > "$T/out" 2>&1
  local code=$?
  set -e
  cat "$T/out" >> "$T/all-out"
  return $code
}

echo "1. 정상 배포(all develop): 원격의 새 커밋을 받아 교체한다"
publish backend v2
publish frontend f2
: > "$STUB_LOG"
if run all develop; then pass "종료 코드 0"; else fail "종료 코드 0"; cat "$T/out"; fi
check "app.jar가 새 버전(v2)" '[ "$(cat "$R/opt/sahmhoot/app.jar")" = v2 ]'
check "이전 jar를 app.jar.bak으로 보관" '[ "$(cat "$R/opt/sahmhoot/app.jar.bak")" = old ]'
check "-plain.jar는 쓰지 않음" '! grep -q plain "$R/opt/sahmhoot/app.jar"'
check "임시 파일(app.jar.new)을 남기지 않음" '[ ! -e "$R/opt/sahmhoot/app.jar.new" ]'
check "중지 → 시작 순서" '[ "$(cat "$STUB_LOG")" = "$(printf "stop sahmhoot\nstart sahmhoot")" ]'
BK="$(ls "$R"/var/backups/sahmhoot/predeploy-*.sql.gz 2>/dev/null | head -1)"
check "DB 백업 파일 생성" '[ -n "$BK" ] && gunzip -c "$BK" | grep -q "fake dump"'
check "백업 파일은 소유자만 읽기(600)" '[ -n "$(find "$BK" -perm 600)" ]'
check "백업 폴더는 소유자만(700)" '[ -n "$(find "$R/var/backups/sahmhoot" -maxdepth 0 -perm 700)" ]'
check "프론트 반영(f2)" '[ "$(cat "$R/var/www/sahmhoot/index.html")" = f2 ]'
check "배포 기록에 브랜치와 두 커밋" 'grep -Eq "all develop backend=[0-9a-f]+ frontend=[0-9a-f]+" "$R/var/log/sahmhoot-deploy.log"'

echo "2. 새 버전이 정상이 아니면 이전 버전으로 되돌린다"
publish backend v3-broken
: > "$STUB_LOG"
if FAIL_JAR=v3-broken run backend develop; then fail "실패로 끝나야 함"; else pass "실패로 끝남"; fi
check "app.jar가 이전 버전(v2)으로 복구" '[ "$(cat "$R/opt/sahmhoot/app.jar")" = v2 ]'
check "되돌렸다고 안내" 'grep -q "이전 버전으로 되돌렸습니다" "$T/out"'
check "중지·시작을 두 번(교체, 복구)" '[ "$(grep -c . "$STUB_LOG")" = 4 ]'
check "실패한 배포는 기록하지 않음" '! grep -q "backend develop" "$R/var/log/sahmhoot-deploy.log"'

echo "2-1. 새 jar 준비(복사)가 중간에 실패하면 서비스를 멈추지 않는다"
publish backend v3-nospace
: > "$STUB_LOG"
BEFORE="$(cat "$R/opt/sahmhoot/app.jar")"
if CP_FAIL=1 run backend develop; then fail "실패로 끝나야 함"; else pass "실패로 끝남"; fi
check "서비스를 건드리지 않음" '[ ! -s "$STUB_LOG" ]'
check "app.jar 그대로" '[ "$(cat "$R/opt/sahmhoot/app.jar")" = "$BEFORE" ]'
check "반쯤 복사된 임시 파일을 지움" '[ ! -e "$R/opt/sahmhoot/app.jar.new" ]'
check "서비스는 그대로라고 안내" 'grep -q "서비스는 그대로" "$T/out"'

echo "2-2. 새 버전 시작(systemctl start)이 실패해도 이전 버전으로 되돌린다"
publish backend v3-nostart
: > "$STUB_LOG"
BEFORE="$(cat "$R/opt/sahmhoot/app.jar")"
if START_FAIL_JAR=v3-nostart run backend develop; then fail "실패로 끝나야 함"; else pass "실패로 끝남"; fi
check "app.jar가 이전 버전으로 복구" '[ "$(cat "$R/opt/sahmhoot/app.jar")" = "$BEFORE" ]'
check "되돌렸다고 안내" 'grep -q "이전 버전으로 되돌렸습니다" "$T/out"'
check "중지·시작을 두 번(교체, 복구)" '[ "$(grep -c . "$STUB_LOG")" = 4 ]'

echo "2-3. app.jar가 비어 있으면 정상본 .bak을 덮지 않는다"
GOOD="$(cat "$R/opt/sahmhoot/app.jar")"
cp "$R/opt/sahmhoot/app.jar" "$R/opt/sahmhoot/app.jar.bak"
: > "$R/opt/sahmhoot/app.jar"
publish backend v3-broken-again
if FAIL_JAR=v3-broken-again run backend develop; then fail "실패로 끝나야 함"; else pass "실패로 끝남"; fi
check ".bak이 정상본 그대로" '[ "$(cat "$R/opt/sahmhoot/app.jar.bak")" = "$GOOD" ]'
check "app.jar가 정상본으로 복구" '[ "$(cat "$R/opt/sahmhoot/app.jar")" = "$GOOD" ]'

echo "3. 필수 키가 없거나 prod가 아니면 아무것도 바꾸지 않고 멈춘다"
grep -v '^APP_ROOM_JOIN_BASE_URL=' "$R/etc/sahmhoot.env" > "$T/env" && mv "$T/env" "$R/etc/sahmhoot.env"
: > "$STUB_LOG"
if run all develop; then fail "실패로 끝나야 함"; else pass "실패로 끝남"; fi
check "빠진 키 이름을 알려 줌" 'grep -q "APP_ROOM_JOIN_BASE_URL" "$T/out"'
check "서비스를 건드리지 않음" '[ ! -s "$STUB_LOG" ]'
write_env
sed 's/^SPRING_PROFILES_ACTIVE=prod$/SPRING_PROFILES_ACTIVE=local/' "$R/etc/sahmhoot.env" > "$T/env" && mv "$T/env" "$R/etc/sahmhoot.env"
if run backend develop; then fail "prod가 아니면 실패해야 함"; else pass "prod가 아니면 실패"; fi
check "프로필 안내" 'grep -q "SPRING_PROFILES_ACTIVE" "$T/out"'
write_env

echo "3-1. 실제로 쓰이는 값이 비었거나 키가 중복되면 멈춘다(systemd는 마지막 줄을 쓴다)"
env_case() { # $1 설명 $2 덧붙일 줄 $3 replace면 같은 키를 먼저 지움
  local key="${2%%=*}"
  write_env
  if [ "${3:-}" = replace ]; then
    grep -v "^${2%%=*}=" "$R/etc/sahmhoot.env" > "$T/env" && mv "$T/env" "$R/etc/sahmhoot.env"
  fi
  printf '%s\n' "$2" >> "$R/etc/sahmhoot.env"
  : > "$STUB_LOG"
  if run backend develop; then fail "$1: 멈춰야 함"; else pass "$1: 멈춤"; fi
  check "$1: 서비스를 건드리지 않음" '[ ! -s "$STUB_LOG" ]'
  check "$1: 키 이름 안내" 'grep -q "$key" "$T/out"'
}
env_case '빈 큰따옴표' 'JWT_SECRET=""' replace
env_case '빈 작은따옴표' "JWT_SECRET=''" replace
env_case '공백만' 'JWT_SECRET=   ' replace
env_case '정상 값 뒤에 빈 값' 'JWT_SECRET='
env_case 'prod 뒤에 local' 'SPRING_PROFILES_ACTIVE=local'
write_env
grep -v '^JWT_SECRET=' "$R/etc/sahmhoot.env" > "$T/env" && mv "$T/env" "$R/etc/sahmhoot.env"
printf '  JWT_SECRET = "%s"  \n' "$SECRET" >> "$R/etc/sahmhoot.env"
if run backend develop; then pass "따옴표·공백으로 감싼 정상 값은 통과"; else fail "따옴표·공백으로 감싼 정상 값은 통과"; cat "$T/out"; fi
write_env

echo "4. 서버 소스에 커밋하지 않은 변경이 있으면 멈춘다"
echo hotfix > "$T/srv/backend/hotfix.txt"
if run backend develop; then fail "실패로 끝나야 함"; else pass "실패로 끝남"; fi
check "변경 안내" 'grep -q "커밋하지 않은 변경" "$T/out"'
rm "$T/srv/backend/hotfix.txt"

echo "5. 없는 브랜치, 잘못된 대상"
if run backend no-such-branch; then fail "없는 브랜치는 실패해야 함"; else pass "없는 브랜치는 실패"; fi
check "브랜치 안내" 'grep -q "no-such-branch" "$T/out"'
if run; then fail "대상 없이 실행하면 실패해야 함"; else pass "대상 없이 실행하면 사용법 안내"; fi

echo "6. 프론트 빌드가 실패하면 백엔드도 바꾸지 않는다"
publish backend v4
: > "$STUB_LOG"
BEFORE="$(cat "$R/opt/sahmhoot/app.jar")"
if NPM_FAIL=1 run all develop; then fail "실패로 끝나야 함"; else pass "실패로 끝남"; fi
check "서비스를 건드리지 않음" '[ ! -s "$STUB_LOG" ]'
check "app.jar 그대로" '[ "$(cat "$R/opt/sahmhoot/app.jar")" = "$BEFORE" ]'

echo "7. 화면 연결이 끊겨도(ssh 끊김) 배포를 끝까지 한다"
RUN_LOG="$T/srv/deploy-last.log"
publish backend v5
publish frontend f5
RECORDS="$(grep -c . "$R/var/log/sahmhoot-deploy.log")"
set +e; bash "$DEPLOY" all develop 2>&1 | head -n 1 > /dev/null; set -e
check "새 백엔드(v5)로 교체" '[ "$(cat "$R/opt/sahmhoot/app.jar")" = v5 ]'
check "프론트 반영(f5)" '[ "$(cat "$R/var/www/sahmhoot/index.html")" = f5 ]'
check "배포 기록 추가" '[ "$(grep -c . "$R/var/log/sahmhoot-deploy.log")" = $((RECORDS + 1)) ]'
check "실행 로그에 완료까지 남음" 'grep -q "완료" "$RUN_LOG"'
check "실행 로그는 주인만 읽기(600)" '[ -n "$(find "$RUN_LOG" -perm 600)" ]'
publish backend v6-broken
set +e; FAIL_JAR=v6-broken bash "$DEPLOY" backend develop 2>&1 | head -n 1 > /dev/null; set -e
check "끊긴 상태에서도 실패한 새 버전을 되돌림(v5)" '[ "$(cat "$R/opt/sahmhoot/app.jar")" = v5 ]'
check "되돌린 사실이 실행 로그에 남음" 'grep -q "이전 버전으로 되돌렸습니다" "$RUN_LOG"'

echo "8. 비밀값이 출력에 나오지 않는다"
check "모든 실행 출력에 비밀값 없음" '! grep -q "$SECRET" "$T/all-out"'
check "실행 로그에 비밀값 없음" '! grep -q "$SECRET" "$RUN_LOG"'

if [ "$FAILS" -eq 0 ]; then echo "모두 통과"; else echo "실패 $FAILS건"; exit 1; fi
