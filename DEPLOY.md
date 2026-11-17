# 서버 구성·배포 절차

2026-11-15 기준 운영 서버(`https://sahmhoot.com`) 구성이다. 비밀값은 이 문서에 쓰지 않는다.

## 구조

GCP Compute Engine VM 한 대에 Nginx, Spring Boot, MySQL을 모두 둔다. 관리형 DB는 쓰지 않는다.

```mermaid
flowchart LR
    U[브라우저] -->|HTTPS 443| NG[Nginx + Let's Encrypt]
    NG -->|/ , SPA 경로| FE[React 빌드 파일<br>/var/www/sahmhoot]
    NG -->|/api/*| BE[Spring Boot<br>127.0.0.1:8080]
    NG -->|/ws WebSocket| BE
    BE -->|JDBC localhost:3306| DB[(MySQL 8.4<br>sahmhoot)]
```

- HTTP(80)는 HTTPS로 301 리디렉션한다.
- 외부에 여는 포트는 80, 443, 22(SSH)다. 8080과 3306은 외부에서 닫혀 있다.
- Nginx가 `/api`, `/ws` 외의 경로는 모두 `index.html`로 돌려준다. 그래서 `/swagger-ui.html` 같은 경로도 200이지만 내용은 React 화면이다.

## 서버 사양

| 항목 | 값 |
|---|---|
| VM | `sahmhoot-server`, e2-medium (2 vCPU, 4GB) |
| 리전 | asia-northeast3 (서울) |
| 디스크 | 29GB |
| OS | Ubuntu 24.04 LTS |
| 외부 IP | 34.47.105.239 (고정) |

이슈 #18은 e2-small·e2-micro를 예정했지만 e2-medium으로 정했다. Java·MySQL·Nginx가 한 VM에서 돌고, #48에서 100명 동시 접속을 확인해야 하기 때문이다.

## 버전

| 구성 요소 | 버전 |
|---|---|
| Java | 21 (OpenJDK 21.0.13) |
| Spring Boot | 4.1.1 |
| MySQL | 8.4.13 LTS (MySQL 공식 APT 저장소 `mysql-8.4-lts`) |
| Nginx | 1.24.0 |
| Node.js | 24 (프론트 빌드용, NodeSource 저장소) |
| 인증서 | Let's Encrypt, Certbot(snap), `sahmhoot.com`·`www.sahmhoot.com` |

## 도메인

- 호스팅케이알에서 `sahmhoot.com`을 쓴다. 네임서버는 호스팅케이알 기본값이다.
- A 레코드 `@`, `www` → 34.47.105.239, TTL 60초.

## 서버 경로

| 경로 | 역할 |
|---|---|
| `~/sahmhoot/backend`, `~/sahmhoot/frontend` | Git 소스·빌드 작업 폴더 (관리자 계정 홈). 배포 스크립트가 배포한 커밋에 고정한다 |
| `/opt/sahmhoot/app.jar` | 실행 중인 백엔드 JAR. 직전 버전은 `app.jar.bak` |
| `/etc/sahmhoot.env` | 백엔드 환경변수. root만 읽게 둔다(`root:root`, 600). 키 목록은 레포 `deploy/sahmhoot.env.example` |
| `/etc/systemd/system/sahmhoot.service` | 백엔드 서비스 정의. `sahmhoot` 계정으로 `java -jar /opt/sahmhoot/app.jar` 실행. 레포 사본 `deploy/systemd/sahmhoot.service`(초안, 서버 파일과 대조 필요) |
| `/var/www/sahmhoot/` | Nginx가 제공하는 프론트 빌드 파일 |
| `/etc/nginx/sites-available/sahmhoot` | 사이트 설정. `sites-enabled/sahmhoot`가 이 파일을 가리킨다. 레포 사본 `deploy/nginx/sahmhoot.conf` |
| `/etc/letsencrypt/` | 인증서. 개인키를 복사하거나 공유하지 않는다 |
| `/var/backups/sahmhoot/` | 배포 직전 DB 백업(`predeploy-*.sql.gz`). root만 읽기, 14일 보관 |
| `/var/log/sahmhoot-deploy.log` | 배포 기록(시각, 브랜치, 커밋, 실행한 계정) |

소스를 바꿔도 서비스는 자동으로 바뀌지 않는다. 아래 재배포 절차를 따라야 한다.

## Nginx 설정

`/etc/nginx/sites-available/sahmhoot` 전문은 레포 [`deploy/nginx/sahmhoot.conf`](deploy/nginx/sahmhoot.conf)에 있다. `# managed by Certbot` 줄은 `certbot --nginx`가 HTTPS를 붙이면서 추가했다. 서버 파일을 바꾸면 레포 사본도 같은 PR에서 고친다.

| 부분 | 역할 |
|---|---|
| `location /` + `try_files ... /index.html` | 파일이 없으면 `index.html`을 준다. React Router 경로를 새로고침해도 404가 나지 않는다 |
| `location /api/` | `/api/*` 요청을 Spring Boot(8080)로 넘긴다. `X-Forwarded-*`로 원래 클라이언트 IP와 https 여부를 전달한다 |
| `location /ws` | WebSocket 업그레이드(`Upgrade`, `Connection` 헤더, HTTP/1.1)를 Spring Boot로 넘긴다. `proxy_read_timeout 3600s`는 수업 중 연결이 60초 기본값에 끊기지 않게 한다 |
| 443 `server` | 인증서 경로와 TLS 설정. 개인키(`privkey.pem`)는 경로만 적혀 있고 내용은 서버 밖으로 꺼내지 않는다 |
| 80 `server` | 두 도메인의 HTTP 요청을 HTTPS로 301 리디렉션한다. 그 외 Host는 404 |

설정을 바꾼 뒤에는 `sudo nginx -t`로 문법을 확인하고 `sudo systemctl reload nginx`로 반영한다.

## 환경변수 (`/etc/sahmhoot.env`)

값은 서버에만 둔다. 키 이름만 확인할 때는 `sudo grep -oE '^[A-Z_]+=' /etc/sahmhoot.env`를 쓴다. 키 목록과 설명은 레포 [`deploy/sahmhoot.env.example`](deploy/sahmhoot.env.example)에 있다.

필수 키(`DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `CORS_ALLOWED_ORIGINS`, `APP_ROOM_JOIN_BASE_URL`, `SPRING_PROFILES_ACTIVE=prod`)가 하나라도 없으면 배포 스크립트가 멈춘다. `application-prod.yml`은 DB 계정·`CORS_ALLOWED_ORIGINS`·`APP_ROOM_JOIN_BASE_URL`의 로컬 기본값을 쓰지 않으므로, 스크립트 없이 재시작해도 이 넷이 없으면 기동하지 않는다.

| 키 | 상태 | 비고 |
|---|---|---|
| `DB_USERNAME`, `DB_PASSWORD` | 설정됨 | MySQL 앱 계정 `sahmhoot_app`@`localhost`. 필수 |
| `JWT_SECRET` | 설정됨 | 필수 |
| `CORS_ALLOWED_ORIGINS` | 설정됨 | `https://sahmhoot.com,https://www.sahmhoot.com`. 필수. 없으면 브라우저 로그인·WebSocket이 403 |
| `SPRING_PROFILES_ACTIVE` | 설정됨 | `prod`여야 한다. 없으면 `local`로 떠서 Swagger가 켜진다 |
| `APP_ROOM_JOIN_BASE_URL` | **미설정** | 필수. `https://sahmhoot.com`으로 설정해야 다음 배포가 진행된다. 없으면 참여 링크·QR이 localhost를 가리킨다 |
| `MAIL_HOST`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` | 미설정 | #21에서 설정. 비어 있으면 인증 코드가 메일 대신 서버 로그에 찍힌다 |
| `ALLOWED_EMAIL_DOMAINS` | 미설정 | 가입 허용 도메인. 운영 전에 정한다 |

`DB_URL`은 기본값(`localhost:3306/sahmhoot`)을 쓴다. 환경변수를 바꾼 뒤에는 `sudo systemctl restart sahmhoot`로 다시 읽게 한다.

## 재배포

레포의 [`deploy/deploy.sh`](deploy/deploy.sh)로 한 번에 한다. 소스 폴더의 주인인 관리자 계정으로 실행한다. 재시작하는 동안 API·WebSocket이 잠시 끊기므로 **수업 중에는 배포하지 않는다.**

```bash
# 내 PC에서 (계정은 서버 관리자 계정)
ssh <계정>@sahmhoot.com 'bash ~/sahmhoot/backend/deploy/deploy.sh all develop'

# 서버에 접속해 있다면
bash ~/sahmhoot/backend/deploy/deploy.sh all develop
```

- 대상: `all`(백엔드 → 프론트 순서), `backend`, `frontend`. 브랜치나 태그를 생략하면 `develop`이다. 최종 배포(#33)는 `deploy.sh all v1.0.0`처럼 태그로 한다.
- 스크립트가 하는 일: 필수 환경변수 확인 → 소스를 원격 브랜치·태그의 커밋에 맞춤 → 백엔드·프론트 빌드 → DB 백업(`/var/backups/sahmhoot`) → JAR 교체·재시작 → `/api/health`가 90초 안에 UP인지 확인 → 프론트 복사 → `/var/log/sahmhoot-deploy.log`에 기록. 빌드가 모두 끝난 뒤에만 서비스를 바꾸므로, 빌드가 실패하면 운영 중인 서버는 그대로다.
- 새 버전이 UP이 되지 않으면 `app.jar.bak`(직전 버전)으로 되돌리고 실패로 끝난다. DB는 되돌리지 않는다. Flyway 마이그레이션은 기동할 때 적용되므로, 필요하면 백업 파일로 복원한다(아래 "백업").
- 멈추는 경우: 필수 키가 없을 때, 서버 소스 폴더에 커밋하지 않은 변경이 있을 때, 브랜치·태그가 없을 때, 다른 배포가 진행 중일 때.
- 소스 폴더는 배포한 커밋에 고정(detached HEAD)된다. 서버에서 `git pull`이나 파일 수정을 하지 않는다. 고칠 것은 PR로 올린다.
- 프론트는 이전 빌드 파일을 지우지 않는다. 이미 열려 있는 화면이 예전 파일을 계속 불러올 수 있게 하기 위해서다.
- 스크립트를 고쳤다면 PR 전에 `bash deploy/test-deploy.sh`로 가짜 서버 시험을 돌린다. 이 스크립트는 서버에서 sudo로 실행되므로 `deploy/` 변경은 리뷰를 받고 머지한다.

### 처음 한 번 (스크립트 도입 전 서버)

스크립트가 develop에 머지된 뒤 한 번만 한다.

1. `/etc/sahmhoot.env`에 `APP_ROOM_JOIN_BASE_URL=https://sahmhoot.com`을 넣는다. 다른 필수 키는 [`deploy/sahmhoot.env.example`](deploy/sahmhoot.env.example)과 비교한다.
2. `systemctl cat sahmhoot` 결과를 [`deploy/systemd/sahmhoot.service`](deploy/systemd/sahmhoot.service)와 비교하고, 다르면 레포 사본을 서버 기준으로 고친다. `Environment=`에 비밀값이 직접 있으면 env 파일로 옮긴다.
3. 두 소스 폴더에 커밋하지 않은 변경이 없는지 확인한다(`git -C ~/sahmhoot/backend status`, 프론트도 같이).
4. 백엔드 소스를 스크립트가 있는 커밋으로 한 번 맞춘 뒤 실행한다.

   ```bash
   git -C ~/sahmhoot/backend fetch origin
   git -C ~/sahmhoot/backend checkout --detach origin/develop
   bash ~/sahmhoot/backend/deploy/deploy.sh all develop
   ```

### 직접 되돌리기

스크립트가 되돌리지 못했거나 배포 뒤에 문제를 발견했을 때 쓴다.

```bash
sudo systemctl stop sahmhoot
sudo cp -p /opt/sahmhoot/app.jar.bak /opt/sahmhoot/app.jar
sudo systemctl start sahmhoot
sudo journalctl -u sahmhoot -n 50 --no-pager   # Started SahmhootApplication 확인
curl -s https://sahmhoot.com/api/health        # {"status":"UP"}
```

## 백업

```bash
sudo mysqldump --single-transaction --routines --triggers --databases sahmhoot > ~/sahmhoot-backup-$(date +%F).sql
chmod 600 ~/sahmhoot-backup-*.sql
```

VM 한 대에 DB가 같이 있으므로, 큰 작업 전에는 GCP 콘솔에서 디스크 스냅샷도 만든다.

배포 스크립트는 백엔드를 교체하기 전에 `/var/backups/sahmhoot/predeploy-날짜-시각.sql.gz`를 만들고 14일 지난 것은 지운다. 정기 자동 백업은 아직 없다(#98). 복원은 아래처럼 한다. 현재 DB를 덮어쓰므로 먼저 지금 상태를 한 번 더 덤프한다. 백업에 없는 테이블(새 마이그레이션이 만든 테이블)은 지워지지 않으므로, 그 마이그레이션을 다시 적용하려면 해당 테이블을 먼저 지운다.

```bash
gunzip -c /var/backups/sahmhoot/predeploy-날짜-시각.sql.gz | sudo mysql
```

## 레포·이슈·PR에 올리지 않는 것

세 레포 모두 공개 저장소다. 한 번 올라간 값은 지워도 git 기록에 남으므로, 실수로 올렸다면 지우는 것으로 끝내지 말고 그 값을 바꾼다.

| 올리지 않는 것 | 두는 곳 |
|---|---|
| 환경변수 값(DB 비밀번호, `JWT_SECRET`, 메일 앱 비밀번호) | 서버 `/etc/sahmhoot.env`, 노션 비공개 페이지 |
| 메일 계정 비밀번호·앱 비밀번호 | 노션 비공개 페이지 |
| SSH 개인키(`.pub`이 아닌 키 파일), GCP 인증 키(`.json`) | 각자 PC |
| 인증서 개인키 `privkey.pem` | 서버 `/etc/letsencrypt/` |
| DB 백업 파일(회원 이메일·비밀번호 해시 포함) | 서버 `/var/backups/sahmhoot/` |
| 서버 로그(사용자 이메일, 메일 설정 전에는 인증 코드 포함) | 서버 `journalctl` |
| `application-local.yml` | 각자 PC (gitignore 대상) |

키 이름(`JWT_SECRET=`)과 파일 경로는 올려도 된다. 캡처 이미지에 값이 보이지 않는지도 확인한다.

## 인증서

- Certbot snap이 설치한 `snap.certbot.renew.timer`가 하루 두 번 갱신 여부를 확인한다.
- 갱신 점검: `sudo certbot renew --dry-run`
- 만료일 확인: `sudo certbot certificates`

## 점검 명령

```bash
sudo systemctl is-active sahmhoot mysql nginx
sudo nginx -t
curl -I https://sahmhoot.com
curl -s https://sahmhoot.com/api/health
```

WebSocket은 Origin을 실제 도메인으로 붙여 확인한다. 101이면 정상, 403이면 `CORS_ALLOWED_ORIGINS`를 확인한다.

```bash
curl -s -o /dev/null -w "%{http_code}\n" --http1.1 \
  -H "Connection: Upgrade" -H "Upgrade: websocket" \
  -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
  -H "Origin: https://sahmhoot.com" https://sahmhoot.com/ws --max-time 3
```
