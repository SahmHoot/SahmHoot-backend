# 삼훗(SahmHoot) — 백엔드

수업 중 실시간 소통 서비스. 산학협력캡스톤디자인 I (2026-2), 팀 강육김권김.
점수·순위 없이 수강생 전체의 이해도를 실시간으로 확인하는 퀴즈·익명 채팅·이모지 반응 서비스입니다.

> 현재 초기 설정 단계입니다. 실행 방법은 프로젝트 뼈대가 올라온 뒤 추가됩니다.

## 기술 스택

| 항목 | 버전 |
|---|---|
| Java | 21 |
| Spring Boot | 4.1.x |
| 빌드 | Gradle (wrapper 포함, 별도 설치 불필요) |
| DB | MySQL 9.7 LTS, Flyway |
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

(뼈대 PR에서 추가)
