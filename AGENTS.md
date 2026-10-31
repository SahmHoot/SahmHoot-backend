# AGENTS.md — SahmHoot-backend

삼훗은 교수와 학생이 같은 수업방에서 퀴즈를 풀고, 교수가 반 전체의 이해도를 확인하는 서비스다. **개인 점수·순위·성적은 만들지 않는다.** 이 저장소는 Spring Boot REST·STOMP 서버다. 프론트엔드는 `SahmHoot-frontend`, 로컬 DB 컨테이너는 `db-schema` 저장소에 있고, 세 저장소를 같은 부모 폴더에 나란히 둔다.

이 문서가 이 프로젝트의 기준이다. 외부 문서(노션 등)를 볼 수 없다고 가정하고 쓴다. 이 문서와 코드가 어긋나면 임의로 판단하지 말고 사용자에게 알린다.

## 환경 (이 버전에 맞춘다)

| 항목 | 값 | 기준 파일 |
|---|---|---|
| JDK | 21 | `build.gradle` toolchain |
| Gradle | 9.7.1 — `./gradlew`만 사용, 별도 설치 금지 | `gradle/wrapper/gradle-wrapper.properties` |
| Spring Boot | 4.1.1 (JPA, Security, WebMVC, WebSocket, Validation, Flyway, Mail) | `build.gradle` |
| DB | MySQL 8.4, 개발 DB `sahmhoot`, 테스트 DB `sahmhoot_test` | `../db-schema/docker.yml` |
| Redis | 7.4 컨테이너만 존재. **앱 코드는 사용하지 않는다** | — |
| 포트 | 백엔드 8080, MySQL 3306, Redis 6379 | `application.yml` |
| 형식 | UTF-8, LF, 들여쓰기 2칸 (`*.gradle`은 4칸) | `.editorconfig` |

## 명령

```bash
# DB (../db-schema 에서)
docker compose -f docker.yml up -d
docker compose -f docker.yml ps          # sahmhoot-mysql 이 healthy 인지 확인

# 최초 1회: 개인 설정 파일 만들고 DB 비밀번호를 docker.yml 값과 맞춘다
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml

./gradlew bootRun
curl http://localhost:8080/api/health    # {"status":"UP"}
./gradlew test                           # sahmhoot_test DB 필요
```

- Windows는 `gradlew.bat`.
- 테스트가 DB 연결로 실패하면 MySQL 컨테이너 상태부터 확인한다.

## 경계

### 항상 한다
- 작업을 끝내기 전에 `./gradlew test`를 실행하고, 실행하지 못했다면 못 했다고 말한다.
- 새 동작에는 테스트를 추가한다: 비즈니스 규칙은 Service 단위 테스트, API는 Controller 테스트, DB 동작은 영속성 통합 테스트 (`quiz`, `questionset`의 기존 테스트가 예시).
- 새 코드는 같은 모듈의 기존 코드 패턴을 따른다.

### 먼저 사용자에게 확인한다
- `build.gradle` 의존성 추가·변경
- 보안 설정(`config/`), WebSocket 설정, `common/` 변경
- Flyway 마이그레이션 새로 추가
- API 필드 이름·오류 코드·오류 응답 형식 변경 (프론트가 깨진다)
- 모듈별로 나뉜 오류 처리기(`auth/exception`, `quiz/exception`, `questionset/exception`, `room/exception`)를 하나로 통합하는 일
- CI·배포 파일(`.github/workflows`, `deploy/`) 변경

### 절대 하지 않는다
- **머지된 Flyway 마이그레이션 수정.** 고칠 때는 새 `V{n}__*.sql`을 추가한다.
- **비밀값 커밋.** `application-local.yml`은 gitignore 대상이다. DB 비밀번호·JWT secret·메일 비밀번호를 코드, 문서, PR, 커밋 메시지에 쓰지 않는다.
- 로그, PR 본문, 오류 출력에 비밀값을 남기는 일.
- `main`, `develop`에 직접 푸시.
- `docker compose ... down -v` (로컬 데이터를 전부 지운다).
- 엔티티에 `@Data`, `@Setter` 사용.
- 요청받지 않은 리팩터링, 포맷만 바꾸는 변경.

## 규칙

### 도메인
- 상태는 세 가지를 **따로** 관리한다: 방 `OPEN/PLAYING/CLOSED`, 회차 `READY/RUNNING/FINISHED/ABORTED`, 실행 문항 `READY/OPEN/CLOSED`. `QUESTION`, `FEEDBACK`, `RESULT`, `ROOM_WAITING`은 화면·이벤트 표현이며 DB 값이 아니다.
- 수업 종료는 삭제가 아니라 `CLOSED` + `closed_at` 기록이다. 종료된 방의 기록은 보존한다.
- 방 주인(교수)은 답안을 제출할 수 없다 (`FORBIDDEN`). 종료된 방은 `ROOM_CLOSED`.
- 답안은 마감 전까지 바꿀 수 있고, 마감 판단은 서버 시각 기준이다.
- 채팅 메시지는 1~300자다 (구현 전).
- 스키마의 기준은 Flyway 마이그레이션(`db/migration`)이다. `../db-schema/sahmhoot.sql`은 설계 참고용이다.
- 시간은 UTC로 저장한다. 실시간 이벤트는 DB 변경이 커밋된 뒤에 발행한다.

### 코드
- 패키지는 기능별(`auth`, `user`, `room`, `questionset`, `quiz`, `chat`, `reaction`, `websocket`, `common`, `config`)이고, 안은 `controller / service / repository / entity / dto / exception`이다. 흐름은 `Controller → Service → Repository → Entity`이며 Controller에 비즈니스 규칙을 넣지 않는다.
- 다른 기능 패키지의 내부를 직접 참조하지 않는다. 필요하면 `quiz/service/port`의 `RoomPort`처럼 인터페이스를 통한다.
- DTO는 Java `record`다. 엔티티를 응답으로 직접 반환하지 않는다.
- 엔티티 Lombok은 `@Getter`, `@NoArgsConstructor(access = PROTECTED)`, `@Builder`만 쓴다. 상태는 setter가 아니라 `close()`처럼 의도가 드러나는 메서드로 바꾸고, 그 안에서 전이 규칙을 검사한다.
- JPA: ID는 `IDENTITY`, Enum은 `EnumType.STRING`, 연관관계는 전부 `LAZY`, cascade는 문제집→문항→선택지에만 쓴다.
- 클래스 이름: `RoomController`, `RoomService`, `RoomRepository`, `CreateRoomRequest`, `RoomResponse`.

### API·실시간
- REST는 `/api/**`, 인증은 JWT.
- 오류 응답: `{ "status": 400, "code": "VALIDATION_FAILED", "message": "...", "fieldErrors": [...] }`. `code`는 `ErrorCode` enum 이름이다.
- STOMP: 엔드포인트 `/ws`, 클라이언트→서버 `/app/**`, 서버→클라이언트 `/topic/**`·`/queue/**`, 개인 오류는 `/user/queue/errors`.

### 오류 처리
- 새 기능 모듈은 자기 `{모듈}/exception` 패키지에 오류 코드 enum, 예외 클래스, 응답 `record`, `@RestControllerAdvice`를 둔다.
- 다른 모듈의 `exception` 클래스를 import하지 않고, 다른 모듈의 오류 코드 enum에 코드를 추가하지 않는다. 겹치는 코드(`FORBIDDEN` 등)도 자기 enum에 다시 선언한다.
- `@RestControllerAdvice`는 `assignableTypes`나 `basePackageClasses`로 자기 컨트롤러에만 적용한다. 범위가 겹치면 다른 모듈의 처리기가 동작하지 않는다.
- 오류 코드 이름과 HTTP 상태는 이슈 본문이나 기존 코드에 있는 것을 그대로 쓴다. 어디에도 없는 코드는 만들기 전에 사용자에게 확인한다.
- 모든 예외를 잡는 `@ExceptionHandler(Exception.class)`를 둘 때도 스프링 기본 오류(415, 405 등)가 500 `INTERNAL_ERROR`로 바뀌지 않게 한다.

## Git·PR

- 브랜치: `develop`에서 `feat/{이슈번호}-{영문}` 또는 `fix/{이슈번호}-{영문}`. 프론트와 같이 고칠 때는 두 저장소에서 같은 이름을 쓴다.
- 커밋 메시지: `type: 한국어 요약 (#이슈번호)`. type은 `feat`, `fix`, `refactor`, `test`, `chore`, `docs`, `style`.
- 이슈는 이 저장소에 만들고 제목에 요구사항 ID를 단다. 예: `[RM-01] 수업방 생성`.
- PR은 `.github/pull_request_template.md`를 채운다. 400줄 이하를 목표로 한다. 백엔드 PR을 먼저 머지하고 프론트 PR이 이를 참조한다.
- 머지는 GitHub의 "Create a merge commit"(일반 머지)으로 한다. Squash는 쓰지 않는다.

## 이 문서 관리

- 코드와 어긋난 줄은 그 변경을 하는 PR에서 같이 고친다.
- 같은 실수가 반복될 때만 규칙을 추가하고, 더 이상 맞지 않는 줄은 지운다.
