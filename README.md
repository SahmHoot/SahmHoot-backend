# 삼훗(SahmHoot) — 백엔드

수업 중 실시간 소통 서비스. 산학협력캡스톤디자인 I (2026-2), 팀 강육김권김.
점수·순위 없이 수강생 전체의 이해도를 실시간으로 확인하는 퀴즈·익명 채팅·이모지 반응 서비스입니다.

## 기술 스택

| 항목 | 버전 |
|---|---|
| Java | 21 |
| Spring Boot | 4.1.x |
| 빌드 | Gradle (wrapper 포함, 별도 설치 불필요) |
| DB | MySQL 8.4 LTS (Docker), Flyway |
| 실시간 | WebSocket + STOMP |

## 관련 링크

- 프론트엔드 레포: https://github.com/SahmHoot/SahmHoot-frontend
- 설계 문서(노션): https://app.notion.com/p/3e1136463d9381cc9790e16a2dd637bd
- 개발 환경·컨벤션: https://app.notion.com/p/3e4136463d938199a3e2f8cf7e4547d5
- 와이어프레임(Figma): https://www.figma.com/design/SUp5dpVNfMF96iK3Vd1nuv

## 협업 규칙 요약

- 이슈는 이 레포(백엔드)에 모읍니다. 프론트 작업도 여기 이슈를 만듭니다.
- 브랜치: `main`(배포본) / `develop`(기본) / `feature/{이슈번호}-{영문}` / `fix/{이슈번호}-{영문}`
- 같은 이슈 작업은 두 레포에 같은 브랜치 이름을 씁니다.
- `main`·`develop` 직접 push 금지, PR 필수. 승인은 권장이고, 공통 영역(`common/`, 보안·WebSocket 설정, 마이그레이션, 빌드 설정) 변경은 1명 확인 후 머지. feature → develop은 Squash merge.
- 커밋: `feat: 수업 방 생성 API 추가 (#12)` (feat / fix / refactor / chore / docs / test / style)
- 자세한 규칙은 노션 07 개발 환경·컨벤션을 봅니다.

## 실행 방법

### 필요한 것

- JDK 21
- Docker (Docker Desktop 또는 colima). DB는 DB 레포의 Docker 설정으로 띄웁니다
- Gradle은 설치하지 않아도 됩니다(`./gradlew`가 알아서 받음)

### 1. DB 실행 (처음 한 번)

DB는 DB 레포 [SahmHoot/db-schema](https://github.com/SahmHoot/db-schema)의 Docker 설정으로 띄웁니다(MySQL 8.4, DB `sahmhoot`, 계정 `sahmhoot_user`, 포트 3306). PC에 따로 설치한 MySQL이 켜져 있으면 3306 포트가 겹치니 먼저 꺼 주세요.

```bash
# 백엔드 레포와 같은 폴더에서
gh repo clone SahmHoot/db-schema
cd db-schema
docker compose -f docker.yml up -d
docker compose -f docker.yml ps   # sahmhoot-mysql이 healthy면 준비 완료
```

- `sahmhoot`는 개발용, `sahmhoot_test`는 테스트용입니다. `sahmhoot_test`는 DB 레포의 `mysql/init`이 컨테이너를 처음 만들 때 자동으로 만듭니다.
- 테스트에서 `Unknown database 'sahmhoot_test'` 오류가 나면(DB 레포에 설정이 반영되기 전에 컨테이너를 만든 경우) 아래를 한 번 실행합니다. root 비밀번호는 `docker.yml`의 `MYSQL_ROOT_PASSWORD`입니다.
  ```bash
  docker exec -it sahmhoot-mysql mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS sahmhoot_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; GRANT ALL PRIVILEGES ON sahmhoot_test.* TO 'sahmhoot_user'@'%';"
  ```
- 테이블은 앱과 테스트가 실행될 때 Flyway가 `src/main/resources/db/migration`의 마이그레이션으로 만듭니다(로컬 시드는 `db/seed`, `local` 프로파일에서만 실행).
- 끄기 `docker compose -f docker.yml stop`, 완전 초기화(데이터 삭제) `docker compose -f docker.yml down -v`

### 2. 로컬 설정 파일 만들기 (처음 한 번)

```bash
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
```

`application-local.yml`의 `password`를 DB 레포 `docker.yml`의 `MYSQL_PASSWORD` 값으로 바꿉니다. 이 파일은 gitignore되어 커밋되지 않습니다.

### 3. 실행

```bash
./gradlew bootRun
```

- 서버 상태: http://localhost:8080/api/health → `{"status":"UP"}`
- API 문서(Swagger): http://localhost:8080/swagger-ui.html

### 테스트 · 포맷

```bash
./gradlew test           # 테스트 (sahmhoot_test DB 사용)
./gradlew spotlessApply  # 코드 포맷 자동 정리 (google-java-format)
./gradlew spotlessCheck  # 포맷 검사만 (CI에서 실행)
```

PR을 올리면 CI(`backend-ci`)가 `./gradlew spotlessCheck build`를 실행합니다. 커밋 전에 `spotlessApply`를 한 번 돌려 주세요.
