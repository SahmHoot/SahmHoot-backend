#!/usr/bin/env bash
# 삼훗 서버 배포 스크립트(#20). 운영 서버의 관리자 계정(소스 폴더 주인)으로 실행한다.
#
#   deploy/deploy.sh <all|backend|frontend> [브랜치 또는 태그]      기본: develop
#   내 PC에서:  ssh <계정>@sahmhoot.com 'bash ~/sahmhoot/backend/deploy/deploy.sh all develop'
#
# 백엔드: 필수 환경변수 확인 → 소스 맞춤 → 빌드 → DB 백업 → jar 교체·재시작 → 상태 확인(실패하면 이전 jar로 되돌림)
# 프론트: 소스 맞춤 → 빌드 → 복사.  마지막에 배포 기록을 남긴다.
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

log() { printf '[deploy] %s\n' "$*"; }
die() { printf '[deploy] 중단: %s\n' "$*" >&2; exit 1; }

usage() {
  echo "사용법: $0 <all|backend|frontend> [브랜치 또는 태그(기본 develop)]" >&2
  exit 1
}

check_env() {
  sudo test -f "$ENV_FILE" || die "$ENV_FILE 이 없습니다."
  local key missing=""
  for key in $REQUIRED_KEYS; do
    sudo grep -Eq "^${key}=.+" "$ENV_FILE" || missing="$missing $key"
  done
  [ -z "$missing" ] || die "$ENV_FILE 에 값이 없는 키:$missing (deploy/sahmhoot.env.example 참고)"
  sudo grep -Eq "^SPRING_PROFILES_ACTIVE=[\"']?prod[\"']?$" "$ENV_FILE" \
    || die "$ENV_FILE 의 SPRING_PROFILES_ACTIVE가 prod가 아닙니다."
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

restart_with() {
  sudo systemctl stop sahmhoot
  sudo cp -p "$1" "$APP_JAR"
  sudo systemctl start sahmhoot
}

deploy_backend() {
  local f jars=() backup
  checkout "$BACKEND_DIR"
  log "백엔드 빌드"
  (cd "$BACKEND_DIR" && ./gradlew --quiet clean bootJar)
  for f in "$BACKEND_DIR"/build/libs/*.jar; do
    case "$f" in *-plain.jar) ;; *) [ -f "$f" ] && jars+=("$f") ;; esac
  done
  [ "${#jars[@]}" -eq 1 ] || die "실행할 jar를 하나로 정하지 못했습니다(build/libs 확인)."

  # Flyway 마이그레이션은 기동할 때 적용되므로 교체 전에 DB를 백업한다.
  backup="$BACKUP_DIR/predeploy-$(date +%Y%m%d-%H%M%S).sql.gz"
  sudo mkdir -p "$BACKUP_DIR"
  sudo chmod 700 "$BACKUP_DIR"
  sudo bash -c 'set -o pipefail; umask 077
    mysqldump --single-transaction --routines --triggers --databases sahmhoot | gzip > "$1"' _ "$backup" \
    || die "DB 백업에 실패해 배포하지 않았습니다."
  sudo find "$BACKUP_DIR" -name 'predeploy-*.sql.gz' -mtime +14 -delete
  log "DB 백업: $backup"

  if sudo test -f "$APP_JAR"; then sudo cp -p "$APP_JAR" "$APP_JAR.bak"; fi
  log "백엔드 교체·재시작"
  restart_with "${jars[0]}"
  if wait_healthy; then
    log "백엔드 정상(UP)"
    DEPLOYED="$DEPLOYED backend=$(git -C "$BACKEND_DIR" rev-parse --short HEAD)"
    return
  fi

  log "새 버전이 ${HEALTH_WAIT_SECONDS}초 안에 정상이 되지 않았습니다. 최근 로그:"
  sudo journalctl -u sahmhoot -n 40 --no-pager || true
  sudo test -f "$APP_JAR.bak" || die "되돌릴 이전 jar가 없습니다. 서버를 직접 확인하세요. DB 백업: $backup"
  log "이전 jar로 되돌립니다"
  restart_with "$APP_JAR.bak"
  if wait_healthy; then
    die "새 버전 배포에 실패해 이전 버전으로 되돌렸습니다(정상). 새 마이그레이션이 적용됐을 수 있으니 필요하면 $backup 으로 DB를 복원하세요."
  fi
  die "이전 버전도 정상이 아닙니다. 서버를 직접 확인하세요. DB 백업: $backup"
}

deploy_frontend() {
  checkout "$FRONTEND_DIR"
  log "프론트 빌드"
  (cd "$FRONTEND_DIR" && npm ci --no-audit --no-fund --loglevel=error && npm run build --silent)
  [ -f "$FRONTEND_DIR/dist/index.html" ] || die "프론트 빌드 결과(dist/index.html)가 없습니다."
  sudo test -d "$WEB_ROOT" || die "$WEB_ROOT 가 없습니다."
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

  [ "$TARGET" = frontend ] || check_env
  case "$TARGET" in
    all) deploy_backend; deploy_frontend ;;
    backend) deploy_backend ;;
    frontend) deploy_frontend ;;
  esac
  record
  log "완료. 확인: curl -s https://sahmhoot.com/api/health"
}

main "$@"
