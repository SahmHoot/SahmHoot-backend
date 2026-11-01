# 삼훗(SahmHoot) — 백엔드

수업 중 실시간 소통 서비스를 위한 백엔드입니다. 산학협력캡스톤디자인 I (2026-2), 팀 강육김권김.
JWT 인증, 문제집 관리와 실시간 퀴즈 기능을 제공합니다.

## 기술 스택

| 항목 | 버전 |
|---|---|
| Java | 21 |
| Spring Boot | 4.1.1 |
| 빌드 | Gradle (wrapper 포함, 별도 설치 불필요) |
| DB | MySQL 8.4 LTS (Docker), Flyway |
| 현재 범위 | JWT 인증, 문제집 CRUD, 실시간 퀴즈, 로컬 시드, Flyway V1 스키마 |

## 현재 구현 범위

- `SahmhootApplication`
- `HealthController` (`GET /api/health`)
- JWT 기반 `SecurityConfig`, 회원가입·로그인·이메일 인증
- 기본 Application Test 및 HealthController Test
- Flyway `V1__init.sql` (10개 테이블 스키마, DB 레포 `sahmhoot.sql`과 동일)
- 문제집 CRUD와 문항·선택지 조회, 퀴즈·답변 API, WebSocket/STOMP
- local 전용 개발 계정과 샘플 문제집 시드
- Swagger/OpenAPI

다음 기능은 아직 구현하지 않았습니다.

- 문항 작성 CRUD API (#50)
- Redis 연결

## 관련 링크

- 프론트엔드 레포: https://github.com/SahmHoot/SahmHoot-frontend
- DB 레포(로컬 DB 실행 설정·ERD): https://github.com/SahmHoot/db-schema
- 설계 문서(노션): https://app.notion.com/p/3e1136463d9381cc9790e16a2dd637bd
- 개발 환경·컨벤션: https://app.notion.com/p/3e4136463d938199a3e2f8cf7e4547d5
- 와이어프레임(Figma): https://www.figma.com/design/SUp5dpVNfMF96iK3Vd1nuv

## 협업 규칙 요약

- 이슈는 이 레포(백엔드)에 모읍니다. 프론트 작업도 여기 이슈를 만듭니다.
- 브랜치: `main`(배포본) / `develop`(기본) / `feat/{이슈번호}-{영문}` / `fix/{이슈번호}-{영문}`
- 같은 이슈 작업은 두 레포에 같은 브랜치 이름을 씁니다.
- `main`·`develop` 직접 push 금지, PR 필수. 승인은 권장이고, 공통 영역(`common/`, 보안·WebSocket 설정, 마이그레이션, `build.gradle`, `package.json`) 변경은 1명 확인 후 머지. feature → develop은 Squash merge.
- 기능 하나 = 백엔드 PR + 프론트 PR. 백엔드를 먼저 머지합니다.
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
- 처음 기동하면 Flyway가 `src/main/resources/db/migration/V1__init.sql`을 적용해 테이블 10개를 만듭니다. 이미 적용된 마이그레이션 파일은 수정하지 않고, 변경은 `V2__...sql`처럼 새 파일로 추가합니다.
- 끄기 `docker compose -f docker.yml stop`, 완전 초기화(데이터 삭제) `docker compose -f docker.yml down -v`

### 2. 로컬 설정 파일 만들기 (처음 한 번)

```bash
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
```

`application-local.yml`의 `password`를 DB 레포 `docker.yml`의 `MYSQL_PASSWORD` 값으로 바꿉니다. 이 파일은 gitignore되어 커밋되지 않습니다.

### 3. 실행

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

- 서버 상태: http://localhost:8080/api/health → `{"status":"UP"}`

### 로컬 시드 데이터 (#52)

`local` 프로파일로 실행하면 Flyway 적용 및 JPA 스키마 검증 후 다음 계정을 자동 생성합니다.
Windows에서는 `./gradlew` 대신 `.\gradlew.bat`를 사용합니다.

| 이메일 | 역할 | 개발 전용 예시 비밀번호 |
|---|---|---|
| professor@local.test | PROFESSOR | Test1234! |
| student1@local.test | STUDENT | Test1234! |
| student2@local.test | STUDENT | Test1234! |
| student3@local.test | STUDENT | Test1234! |

이 비밀번호는 **개발 전용 공개 예시**입니다. 운영 계정에 사용하지 마세요.
비밀번호는 기존 BCrypt `PasswordEncoder`로 암호화해 저장합니다. 학번은 비워 두며,
시드 계정은 이메일 인증 완료 상태로 생성되어 `POST /api/auth/login`으로 바로 로그인할 수 있습니다.

교수 계정 소유의 PUBLIC 문제집 `[로컬 샘플] 컴퓨터 기초 퀴즈`도 생성합니다.
객관식 1문항(선택지 4개)과 OX 1문항(`O`, `X`)이며, 각 문항의 정답은 하나입니다.
교수 로그인 후 `GET /api/question-sets/mine`에서 ID를 확인하고,
로그인한 계정으로 `GET /api/question-sets/{id}`에서 문항과 선택지를 조회할 수 있습니다.

- `local`이 있고 `test`, `prod`가 모두 없는 경우에만 시드 Bean이 등록됩니다.
  `prod`, `local,prod`, `local,test` 등의 실행에서는 생성하지 않습니다.
  기본 프로파일은 `local`이므로 운영에서는 반드시 `prod`를 명시하세요.
- 기존 이메일의 계정은 비밀번호·이름·학번을 변경하지 않습니다. 기존 비밀번호로 로그인하세요.
  기존 계정의 역할이 예시와 다르면 전체 시드 트랜잭션을 롤백하고 기동을 실패시킵니다.
- 문제집은 교수 ID와 제목으로 확인합니다. 같은 소유자·제목의 세트가 있으면
  문항을 추가하거나 기존 내용을 덮어쓰지 않습니다. 제목을 바꾸면 다음 실행에서 샘플을 새로 만듭니다.
- 생성 전체는 한 트랜잭션이며 기존 데이터를 삭제하지 않습니다.
  Flyway V1에는 시드 데이터를 넣지 않습니다.

로그인 예시:

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"professor@local.test","password":"Test1234!"}'
```

### 테스트

```bash
./gradlew test           # 테스트 (sahmhoot_test DB 사용)
```

시드 통합 테스트는 테스트 DB를 대상으로 실제 `prod`·`local` 기동, 로그인·PUBLIC 세트 조회,
재기동 중복 방지, 생성 실패 롤백과 기존 데이터 보존을 검증합니다.
`TEST_DB_URL`로 테스트 DB를 지정할 수 있습니다. 시드 예시 이메일이 없는 별도 테스트 DB를 사용하세요.
테스트는 자동 생성 ID를 사용하고 자신이 만든 데이터만 정리합니다.
