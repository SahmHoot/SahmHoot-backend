#!/usr/bin/env bash
# 삼훗 서버 배포 스크립트(#20). 운영 서버의 관리자 계정(소스 폴더 주인)으로 실행한다.
#
#   deploy/deploy.sh <all|backend|frontend> [브랜치 또는 태그]      기본: develop
#   내 PC에서:  ssh <계정>@sahmhoot.com 'bash ~/sahmhoot/backend/deploy/deploy.sh all develop'
#   접속이 끊겨도 끝까지 실행된다. 이어서 볼 때: tail -f ~/sahmhoot/deploy-last.log
#
# 순서: sudo·배포 폴더·필수 환경변수 확인 → 소스 맞춤 → 빌드(백엔드·프론트) → DB 백업
#       → 지금 jar를 .bak으로 보관, 새 jar를 .new로 준비 → 멈춤·교체·시작
#       → 상태 확인(교체·시작·확인 중 실패하면 이전 jar로 되돌림) → 프론트 복사 → 배포 기록.
# 비밀값은 /etc/sahmhoot.env에만 있다. 이 스크립트는 키가 있는지만 확인하고 값은 읽거나 출력하지 않는다.
# 재시작하는 동안 접속이 끊기므로 수업 중에는 실행하지 않는다.
set -euo pipefail

# 테스트(deploy/test-deploy.sh)에서만 바꾼다. 운영에서는 비어 있다.
PREFIX="${SAHMHOOT_DEPLOY_PREFIX:-}"

ENV_FILE="$PREFIX/etc/sahmhoot.env"
APP_JAR="$PREFIX/opt/sahmhoot/app.jar"
WEB_ROOT="$PREFIX/var/www/sahmhoot"
BACKUP_DIR="$PREFIX/var/backups/sahmhoot"
DEPLOY_LOG="$PREFIX/var/log/sahmhoot-deploy.log"
HEALTH_URL="http://127.0.0.1:8080/api/health"
HEALTH_WAIT_SECONDS=90
REQUIRED_KEYS="DB_USERNAME DB_PASSWORD JWT_SECRET CORS_ALLOWED_ORIGINS APP_ROOM_JOIN_BASE_URL"

# 실행 로그(RUN_LOG)를 연 뒤에는 모든 출력이 그 파일로 가고, 안내 줄만 화면(3번)에도 보낸다.
# ssh가 끊겨 화면 쪽이 닫혀도 쓰기 실패를 무시하고 배포를 계속한다.
# 화면 쓰기는 외부 cat이 한다. bash 내장 printf가 닫힌 화면에 쓰다 실패하면 남은 글자가
# 버퍼에 남아 다음 $(...) 결과에 섞이기 때문이다(시험 7에서 확인).
to_terminal() { [ -z "${RUN_LOG:-}" ] || printf '%s\n' "$1" | { cat >&3; } 2>/dev/null || true; }
log() { printf '[deploy] %s\n' "$*"; to_terminal "[deploy] $*"; }
die() { DIED=1; printf '[deploy] 중단: %s\n' "$*" >&2; to_terminal "[deploy] 중단: $*"; exit 1; }

# die를 거치지 않고 set -e로 끝난 경우(빌드·git·복사 실패 등)에도 서비스 상태를 알려 준다.
on_exit() {
  local rc=$?
  { [ "$rc" -eq 0 ] || [ -n "${DIED:-}" ]; } && return
  local msg
  msg="예상하지 못한 오류로 중단했습니다(종료 코드 $rc). sahmhoot 상태: $(sudo systemctl is-active sahmhoot 2>/dev/null || true). 자세한 내용: ${RUN_LOG:-위 출력}"
  printf '[deploy] %s\n' "$msg" >&2
  to_terminal "[deploy] $msg"
}

usage() {
  echo "사용법: $0 <all|backend|frontend> [브랜치 또는 태그(기본 develop)]" >&2
  DIED=1
  exit 1
}

# systemd EnvironmentFile이 실제로 쓰는 값으로 확인한다: 같은 키는 마지막 줄이 쓰이고,
# 값은 앞뒤 공백과 감싼 따옴표를 뺀 것이다. 그래서 "", '', 공백만, 중복 키는 막는다.
# 값은 awk 밖으로 꺼내지 않고 문제 있는 키 이름만 돌려준다.
check_env() {
  sudo test -f "$ENV_FILE" || die "$ENV_FILE 이 없습니다."
  local bad
  bad="$(sudo awk -v req="$REQUIRED_KEYS SPRING_PROFILES_ACTIVE" -v q="'" '
    /^[ \t]*([#;]|$)/ { next }
    {
      i = index($0, "="); if (i == 0) next
      k = substr($0, 1, i - 1); v = substr($0, i + 1)
      gsub(/^[ \t]+|[ \t]+$/, "", k); gsub(/^[ \t]+|[ \t]+$/, "", v)
      f = substr(v, 1, 1)
      if (length(v) >= 2 && (f == "\"" || f == q) && substr(v, length(v), 1) == f) v = substr(v, 2, length(v) - 2)
      gsub(/^[ \t]+|[ \t]+$/, "", v)
      n[k]++; val[k] = v
    }
    END {
      m = split(req, r, " ")
      for (j = 1; j <= m; j++) {
        k = r[j]
        if (n[k] > 1) print k "(중복)"
        else if (val[k] == "") print k "(없음·빈 값)"
      }
      if (n["SPRING_PROFILES_ACTIVE"] == 1 && val["SPRING_PROFILES_ACTIVE"] != "" && val["SPRING_PROFILES_ACTIVE"] != "prod")
        print "SPRING_PROFILES_ACTIVE(prod 아님)"
    }' "$ENV_FILE" | tr '\n' ' ')" || die "$ENV_FILE 을 읽지 못했습니다."
  [ -z "$bad" ] || die "$ENV_FILE 확인 필요: ${bad}(deploy/sahmhoot.env.example 참고)"
}

# 원격의 브랜치·태그가 가리키는 커밋으로 소스를 맞춘다. 서버에서 직접 고친 파일이 있으면 멈춘다.
checkout() {
  local dir="$1" commit
  [ -z "$(git -C "$dir" status --porcelain)" ] \
    || die "$dir 에 커밋하지 않은 변경이 있습니다. 서버에서 직접 고친 파일이 있는지 확인하세요."
  git -C "$dir" fetch --quiet --prune --tags origin
  commit="$(git -C "$dir" rev-parse --verify --quiet "origin/$REF^{commit}" \
    || git -C "$dir" rev-parse --verify --quiet "refs/tags/$REF^{commit}")" \
    || die "$dir 에 '$REF' 브랜치·태그가 없습니다."
  git -C "$dir" checkout --quiet --detach "$commit"
  log "$(basename "$dir"): $REF → $(git -C "$dir" log -1 --format='%h %s')"
}

wait_healthy() {
  local i body
  for i in $(seq "$HEALTH_WAIT_SECONDS"); do
    if body="$(curl -fsS --max-time 2 "$HEALTH_URL" 2>/dev/null)" && [[ "$body" == *'"status":"UP"'* ]]; then
      return 0
    fi
    sleep 1
  done
  return 1
}

# $1을 app.jar 옆 임시 파일로 끝까지 복사해 둔다. 서비스는 아직 그대로다.
# -p 없이 복사해 운영 jar를 root 소유로 둔다(빌드한 계정이 sudo 없이 바꿀 수 없게).
stage_jar() {
  sudo cp "$1" "$APP_JAR.new" && sudo test -s "$APP_JAR.new" && return 0
  sudo rm -f "$APP_JAR.new"
  return 1
}

# 같은 폴더 안의 mv는 원자적이라 반쯤 복사된 jar가 app.jar가 되는 일이 없다.
# 실행 중인 jar를 바꾸면 종료 중인 프로세스가 NoClassDefFoundError를 낼 수 있어 먼저 멈춘다.
swap_and_start() {
  sudo systemctl stop sahmhoot && sudo mv -f "$APP_JAR.new" "$APP_JAR" && sudo systemctl start sahmhoot
}

rollback() { # $1 DB 백업 경로
  sudo test -s "$APP_JAR.bak" || die "되돌릴 이전 jar가 없습니다. 서버를 직접 확인하세요. DB 백업: $1"
  log "이전 jar로 되돌립니다"
  if stage_jar "$APP_JAR.bak" && swap_and_start && wait_healthy; then
    die "새 버전 배포에 실패해 이전 버전으로 되돌렸습니다(정상). 새 마이그레이션이 적용됐을 수 있으니 필요하면 $1 으로 DB를 복원하세요."
  fi
  die "이전 버전도 정상이 아닙니다. 서버를 직접 확인하세요. DB 백업: $1"
}

build_backend() {
  local f jars=()
  log "백엔드 빌드"
  # 빌드가 끝나면 Gradle 데몬이 운영 서버 메모리에 남지 않게 한다.
  (cd "$BACKEND_DIR" && ./gradlew --no-daemon --quiet clean bootJar)
  for f in "$BACKEND_DIR"/build/libs/*.jar; do
    case "$f" in *-plain.jar) ;; *) [ -f "$f" ] && jars+=("$f") ;; esac
  done
  [ "${#jars[@]}" -eq 1 ] || die "실행할 jar를 하나로 정하지 못했습니다(build/libs 확인)."
  NEW_JAR="${jars[0]}"
}

build_frontend() {
  log "프론트 빌드"
  (cd "$FRONTEND_DIR" && npm ci --no-audit --no-fund --loglevel=error && npm run build --silent)
  [ -f "$FRONTEND_DIR/dist/index.html" ] || die "프론트 빌드 결과(dist/index.html)가 없습니다."
}

release_backend() {
  local backup
  # Flyway 마이그레이션은 기동할 때 적용되므로 교체 전에 DB를 백업한다.
  # 같은 초에 만든 다른 백업을 덮거나 지우지 않게 프로세스 번호를 붙인다.
  backup="$BACKUP_DIR/predeploy-$(date +%Y%m%d-%H%M%S)-$$.sql.gz"
  sudo mkdir -p "$BACKUP_DIR"
  sudo chmod 700 "$BACKUP_DIR"
  sudo bash -c 'set -o pipefail; umask 077
    mysqldump --single-transaction --routines --triggers --databases sahmhoot | gzip > "$1"' _ "$backup" \
    || { sudo rm -f "$backup"; die "DB 백업에 실패해 배포하지 않았습니다. 서비스는 그대로입니다."; }
  sudo find "$BACKUP_DIR" -name 'predeploy-*.sql.gz' -mtime +14 -delete
  log "DB 백업: $backup"

  # 비어 있는 jar(이전 배포가 중간에 끊긴 경우 등)로 정상본 .bak을 덮지 않는다.
  if sudo test -s "$APP_JAR"; then
    sudo cp -p "$APP_JAR" "$APP_JAR.bak.tmp" && sudo mv -f "$APP_JAR.bak.tmp" "$APP_JAR.bak"
  fi
  stage_jar "$NEW_JAR" || die "새 jar를 준비하지 못했습니다(디스크 공간 확인). 서비스는 그대로입니다."
  log "백엔드 교체·재시작"
  # 멈춤·교체·시작·상태 확인 중 어느 것이 실패해도 되돌리기로 간다.
  if swap_and_start && wait_healthy; then
    log "백엔드 정상(UP)"
    DEPLOYED="$DEPLOYED backend=$(git -C "$BACKEND_DIR" rev-parse --short HEAD)"
    return
  fi

  log "새 버전이 정상이 되지 않았습니다(교체·시작 실패 또는 ${HEALTH_WAIT_SECONDS}초 안에 UP 아님). 서비스 로그는 $RUN_LOG 에 남긴다."
  sudo journalctl -u sahmhoot -n 40 --no-pager || true
  rollback "$backup"
}

release_frontend() {
  # 이전 빌드 파일은 지우지 않는다. 이미 열려 있는 화면이 예전 파일을 계속 불러올 수 있게 하기 위해서다.
  sudo cp -a "$FRONTEND_DIR/dist/." "$WEB_ROOT/"
  log "프론트 반영 완료"
  DEPLOYED="$DEPLOYED frontend=$(git -C "$FRONTEND_DIR" rev-parse --short HEAD)"
}

record() {
  local line
  line="$(date '+%F %T') $TARGET $REF$DEPLOYED by=$(whoami)"
  sudo mkdir -p "$(dirname "$DEPLOY_LOG")"
  printf '%s\n' "$line" | sudo tee -a "$DEPLOY_LOG" >/dev/null
  log "기록: $line"
}

# 소스를 바꾸면 이 파일도 바뀌므로 전체를 함수로 감싸 끝까지 읽은 뒤 실행한다.
main() {
  # ssh 접속이 끊겨도(HUP, 닫힌 출력의 PIPE) 중간에 죽지 않는다. 자식 명령에도 그대로 이어진다.
  trap '' HUP PIPE
  trap on_exit EXIT
  TARGET="${1:-}"
  REF="${2:-develop}"
  DEPLOYED=""
  case "$TARGET" in all | backend | frontend) ;; *) usage ;; esac

  BACKEND_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  FRONTEND_DIR="$(dirname "$BACKEND_DIR")/frontend"
  [ -d "$FRONTEND_DIR/.git" ] || [ "$TARGET" = backend ] || die "$FRONTEND_DIR 에 프론트엔드 저장소가 없습니다."

  # 두 사람이 동시에 배포하지 않게 막는다.
  exec 9>"$(dirname "$BACKEND_DIR")/.deploy.lock"
  flock -n 9 || die "다른 배포가 진행 중입니다."

  # 빌드·명령 출력은 이 파일에만 남는다(서버 로그 일부가 들어갈 수 있어 주인만 읽기).
  RUN_LOG="$(dirname "$BACKEND_DIR")/deploy-last.log"
  (umask 077 && : > "$RUN_LOG")
  exec 3>&1 >>"$RUN_LOG" 2>&1
  log "실행 로그: $RUN_LOG (접속이 끊겨도 배포는 계속된다. 다시 접속해 tail -f로 확인)"

  local backend=false frontend=false
  [ "$TARGET" = frontend ] || backend=true
  [ "$TARGET" = backend ] || frontend=true

  # 확인·소스 맞춤·빌드가 모두 끝난 뒤에만 실행 중인 서비스를 바꾼다.
  # 그래서 프론트 빌드가 실패해도 백엔드만 새 버전이 되는 일이 없다.
  sudo -n true 2>/dev/null || die "비밀번호 없이 sudo를 쓸 수 있는 관리자 계정으로 실행하세요."
  if $frontend; then sudo test -d "$WEB_ROOT" || die "$WEB_ROOT 가 없습니다."; fi
  if $backend; then check_env; checkout "$BACKEND_DIR"; fi
  if $frontend; then checkout "$FRONTEND_DIR"; fi
  if $backend; then build_backend; fi
  if $frontend; then build_frontend; fi
  if $backend; then release_backend; fi
  if $frontend; then release_frontend; fi
  record
  log "완료. 확인: curl -s https://sahmhoot.com/api/health"
}

main "$@"
