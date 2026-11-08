# 퀴즈 부하 시나리오 (JMeter)

수업 한 개에서 학생 N명이 로그인 → 입장 → 구독 → 답 제출을 하는 시나리오다(#47). 100명 부하 테스트(#48)와 채팅·이모지 포함 재측정에 같은 스크립트를 쓴다.

## 파일

| 파일 | 내용 |
| --- | --- |
| `sahmhoot-quiz.jmx` | 시나리오. 준비 → 학생 N명·교수 1명 동시 실행 → 정리 |
| `Stomp.groovy` | 최소 STOMP 1.2 클라이언트(JDK WebSocket). JMeter WebSocket 플러그인 없이 동작한다 |
| `scripts/*.groovy` | 단계별 스크립트 |
| `accounts.sh` | 부하 테스트 학생 계정 SQL 생성 |
| `monitor.sh` | 프로세스 CPU·메모리 1초 간격 기록 |

## 시나리오

1. **준비**(1회): 교수 로그인 → 문제 세트(객관식 4지 1개, OX 1개) 생성 → 방 생성
2. **학생**(N명, ramp-up): 로그인(HTTP) → 입장(HTTP) → WebSocket 연결·CONNECT·구독(퀴즈 채널, 오류 큐) → 문항마다 [열림 수신 → 무작위 답 제출 → 마감 수신] → 결과 수신 → 연결 종료
3. **교수**(1명): WebSocket 연결·구독(퀴즈 채널, 응답 현황 큐) → 접속 인원이 N명이 될 때까지 대기 → 퀴즈 시작(HTTP) → 종료 뒤 문항마다 응답 현황 최댓값과 최종 결과의 응답 수가 N인지 확인
4. **정리**: 방 종료, 지표 요약 출력

학생 전원이 답하면 문항이 조기 마감된다(#28). 그래서 문항 전환·결과 발행까지 측정된다. 접속 인원이 시간 안에 N명이 되지 않거나, 기다리던 메시지가 오지 않거나, 서버가 ERROR를 보내거나, 응답 수가 N과 다르면 해당 단계가 실패한다.

## 준비

- JMeter 5.6 이상(`brew install jmeter`), Java 17 이상
- 학생 계정: 가입에는 메일 인증이 필요하므로 DB에 바로 넣는다. 이메일은 `load{번호}@load.test`이고 번호는 JMeter 스레드 번호(1~N)와 같다. 이미 있는 계정은 비밀번호만 바뀌므로 다시 실행해도 된다.

  ```bash
  cd test/jmeter
  LOAD_PASSWORD='부하용 비밀번호' ./accounts.sh 100 > /tmp/accounts.sql
  docker exec -i sahmhoot-mysql mysql -u<DB 사용자> -p sahmhoot < /tmp/accounts.sql   # 로컬
  ```

  비밀번호는 저장소·PR에 쓰지 않는다. 실제 사용자 계정이 있는 DB에는 넣지 않는다.
- 교수 계정: 아무 교수 계정. 로컬은 시드 계정(`LocalSeedRunner`)을 쓸 수 있다.

## 실행

스크립트 경로가 실행 위치 기준이므로 **`test/jmeter`에서 실행한다.**

```bash
cd test/jmeter
export PROFESSOR_PASSWORD='<교수 비밀번호>' LOAD_PASSWORD='<학생 계정 비밀번호>'
jmeter -n -t sahmhoot-quiz.jmx \
  -Jusers=30 -Jrampup=6 \
  -JprofessorEmail=<교수 이메일> \
  -JmetricsFile=/tmp/load/metrics.csv -l /tmp/load/result.jtl -e -o /tmp/load/report
```

| 속성 | 기본값 | 설명 |
| --- | --- | --- |
| `protocol`, `host`, `port` | `http`, `localhost`, `8080` | 서버 주소. `https`면 WebSocket은 `wss` |
| `users` | 10 | 학생 수 |
| `rampup` | 5 | 학생을 모두 시작하는 데 걸리는 초 |
| `professorEmail` | 없음 | 교수 계정 이메일 |
| `studentPrefix` | `load` | 학생 계정 이메일 접두사 |
| `questionSeconds` | 20 | 문항 제한시간(10~300) |
| `readyTimeoutSeconds` | 120 | 접속 인원이 N명이 될 때까지 기다리는 최대 초 |
| `metricsFile` | `metrics.csv` | 지표 요약 파일 |

비밀번호는 환경 변수 `PROFESSOR_PASSWORD`(교수), `LOAD_PASSWORD`(학생, `accounts.sh`와 같은 값)로만 받는다. JMeter는 `-J` 속성 값을 `jmeter.log`에 그대로 남기므로 비밀번호를 `-J`로 넘기지 않는다.

서버 자원은 다른 터미널에서 기록한다: `./monitor.sh "$(pgrep -f SahmhootApplication)" server.csv`

## 결과 보기

- 콘솔 마지막 `summary =` 줄의 `Err`가 0%여야 한다. 실패가 있으면 `result.jtl`의 `responseMessage`에 이유가 있다
- `-e -o`로 만든 HTML 보고서에서 단계별 응답 시간을 본다. `문항 열림 수신`·`문항 마감 수신`·`결과 수신`·`접속 인원 확인`·`응답 수·결과 확인`은 메시지를 **기다린 시간**이라 서버 응답 시간이 아니다
- 서버 처리 지연은 `metricsFile`의 지표로 본다(표본과 섞지 않으려고 따로 모은다)

| 지표 | 정의 |
| --- | --- |
| `start_to_first_open_ms` | 퀴즈 시작 요청 직전 → 학생이 첫 QUESTION_OPENED를 받은 시각 |
| `transition_ms(nextOpensAt→OPENED)` | 직전 QUESTION_CLOSED의 `nextOpensAt`(다음 문항 예정 시각) → 학생이 다음 QUESTION_OPENED를 받은 시각. 문항 전환 지연(NFR-02) |
| `last_answer_to_closed_ms` | 그 문항의 마지막 답을 보낸 시각 → 학생이 QUESTION_CLOSED를 받은 시각. 답 처리 + 조기 마감 지연 |
| `delivery_ms(...)` | 메시지 `sentAt`(서버가 메시지를 만든 시각) → 학생이 받은 시각. 서버 발행·전달 지연 |

수신 시각은 WebSocket 리스너가 프레임을 받은 순간에 기록한다. `transition`·`delivery`는 서버 시각과 JMeter PC 시각을 비교하므로, 원격 서버에서는 두 시계의 차이가 그대로 더해진다(측정 전 NTP 동기 상태를 기록한다).

## 데이터

- 실행마다 문제 세트 1개와 방 1개가 생기고, 방은 정리 단계에서 종료된다. 수업 기록 보존 규칙 때문에 참여·답안 기록은 남는다
- 학생 계정은 다시 쓴다. 계정을 지우려면 참여·답안 기록을 먼저 지워야 하므로(외래 키) 테스트 전용 DB에서만 정리한다

## 사전 점검 결과 (2026-11-09, 로컬)

환경: 서버·JMeter·MySQL·Redis를 같은 PC(Apple M5 10코어, 16GB, Java 21)에서 실행. develop `f298087`(#28 포함), `local` 프로필. 문항 2개, 제한시간 20초, 학생 전원 즉시 응답. 10명·30명 각 2회.

| 항목 | 10명 1회 | 10명 2회 | 30명 1회 | 30명 2회 |
| --- | --- | --- | --- | --- |
| 표본·실패 | 106·0 | 106·0 | 306·0 | 306·0 |
| 로그인 평균/최대(ms) | 79/82 | 82/89 | 82/87 | 82/89 |
| 입장 평균/최대(ms) | 8/10 | 8/9 | 8/9 | 8/16 |
| 시작 → 첫 문항 수신 평균(ms) | 28 | 31 | 28 | 24 |
| 문항 전환 지연 평균/최대(ms) | 28/29 | 29/30 | 31/43 | 24/30 |
| 마지막 답 → 마감 수신 평균/최대(ms) | 135/157 | 146/185 | 328/371 | 330/356 |
| 전달 지연 QUESTION_OPENED 평균/최대(ms) | 6/8 | 7/9 | 10/23 | 9/17 |
| 서버 CPU 최대·RSS 최대 | 68%·330MB | 48%·332MB | 47%·340MB | 46%·345MB |
| JMeter CPU 최대 | 48% | 24% | 86% | 90% |

- 모든 실행에서 교수 응답 현황 최댓값과 최종 결과의 문항별 응답 수가 학생 수와 같았다
- **마지막 답 → 마감 수신이 인원에 비례해 늘었다**(10명 약 140ms, 30명 약 330ms). 답 저장이 방 잠금 안에서 순서대로 처리되기 때문으로 보인다(답 1개 약 10ms). 같은 비율이면 100명에서 1초에 가까워지므로 #48에서 우선 확인한다
- 로그인 약 80ms는 BCrypt 비용이 대부분이다. 100명이 동시에 로그인하면 CPU 사용이 몰릴 수 있어 #48에서 ramp-up을 함께 기록한다
- 같은 PC에서 실행해 네트워크 지연이 없고, 30명에서 JMeter CPU가 90%까지 올라 서버와 자원을 나눠 썼다. 배포 서버 측정(#48)은 JMeter를 다른 PC에서 실행한다
- 측정하지 않은 경로: 일부 미응답(제한시간 마감), 답 수정, 마감 뒤 늦은 답, 진행 중 연결 끊김. 채팅·이모지는 기능 완성 뒤 시나리오에 추가한다
