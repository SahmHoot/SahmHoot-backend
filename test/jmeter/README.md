# 퀴즈 부하 시나리오 (JMeter)

수업 한 개에서 학생 N명이 로그인 → 입장 → 구독 → 답 제출을 하는 시나리오다(#47). 100명 부하 테스트(#48)와 채팅·이모지 포함 재측정에 같은 스크립트를 쓴다.

## 파일

| 파일 | 내용 |
| --- | --- |
| `sahmhoot-quiz.jmx` | 시나리오. 준비 → 학생 N명·교수 1명 동시 실행 → 정리 |
| `Stomp.groovy` | 최소 STOMP 1.2 클라이언트(JDK WebSocket). JMeter WebSocket 플러그인 없이 동작한다 |
| `scripts/*.groovy` | 단계별 스크립트 |
| `accounts.sh` | 부하 테스트 계정(학생·교수) SQL 생성 |
| `cleanup.sh` | 실행에서 만든 방·문제 세트와 그 기록을 지우는 SQL 생성 |
| `monitor.sh` | 프로세스 CPU·메모리 1초 간격 기록 |

## 시나리오

1. **준비**(1회): 교수 로그인 → 문제 세트(객관식 4지 1개, OX 1개) 생성 → 방 생성
2. **학생**(N명, ramp-up): 로그인(HTTP) → 입장(HTTP) → WebSocket 연결·CONNECT·구독 → 구독 RECEIPT 수신 → 수업 상태 조회(HTTP, 여기까지 성공하면 준비 완료) → 문항마다 [열림 수신 → 무작위 답 제출 → 마감 수신] → 결과 수신 → 연결 종료
3. **교수**(1명): WebSocket 연결·구독 → 구독 RECEIPT 수신 → 학생 N명이 모두 준비될 때까지 대기 → 접속 인원이 N명인지 확인 → 퀴즈 시작(HTTP) → 종료 뒤 문항마다 응답 현황 최댓값과 최종 결과의 응답 수가 N인지 확인
4. **정리**: 방 종료, 방·문제 세트 ID 기록(`runsFile`), 지표 요약 출력

구독과 상태 조회는 실제 수업 화면과 같게 한다(프론트 `shared/stomp/roomSync.ts`, 06 1절 연결 절차). 학생은 퀴즈·채팅·반응·접속 인원 채널과 개인 큐(`/user/queue/rooms/{id}/me`), 교수는 여기에 응답 현황 큐(`stats`)를 더 구독하고, 둘 다 오류 큐를 구독한다. 그래서 접속 인원(ONLINE_COUNT) 발행과 연결마다 나가는 상태 조회도 부하에 들어간다. 시나리오가 쓰지 않는 채널의 메시지는 받기만 하고 보관하지 않는다. 프론트의 구독 목록이 바뀌면 `Stomp.groovy`의 `roomDestinations`도 바꾼다.

구독마다 `receipt` 헤더를 붙이고, 서버가 구독을 브로커에 등록한 뒤 보내는 RECEIPT를 모두 받을 때까지 기다린다. 교수는 학생 전원이 준비된 뒤에 퀴즈를 시작하므로 등록이 늦은 학생이 첫 문항 열림을 놓치지 않는다. RECEIPT는 등록 완료만 뜻하고, 등록 전에 발행된 메시지를 다시 보내 주지는 않는다.

학생 전원이 답하면 문항이 조기 마감된다(#28). 그래서 문항 전환·결과 발행까지 측정된다. 준비가 시간 안에 끝나지 않거나, 기다리던 메시지·RECEIPT가 오지 않거나, 서버가 ERROR를 보내거나, 응답 수가 N과 다르면 해당 단계가 실패한다.

**실패 처리**: 어느 표본이든 실패하면 시험 계획 전체에 걸린 `실패 처리` 후처리기가 실패 수를 세고, 그 스레드의 WebSocket을 닫는다. 연결이 남아 있으면 서버가 그 학생을 계속 접속 중으로 세어 조기 마감이 되지 않고, 나머지 학생이 제한시간까지 기다려 `last_answer_to_closed_ms`가 약 20초로 튄다. 교수는 준비를 기다리는 중에 실패한 표본이 생기면 바로 실패하고 실행을 멈춘다. 실패가 있었던 실행은 지표 파일의 `failed_samples` 행이 0보다 크고, 그 실행의 지표는 결과로 쓰지 않는다.

## 준비

- JMeter 5.6 이상(`brew install jmeter`), Java 17 이상
- 계정: 가입에는 메일 인증이 필요하므로 DB에 바로 넣는다. 학생 이메일은 `load{번호}@load.test`이고 번호는 JMeter 스레드 번호(1~N)와 같다. 교수는 부하 전용 계정 `loadprof1@load.test`를 쓴다(실제 교수 계정에 문제 세트·방이 쌓이지 않게). 이미 있는 계정은 비밀번호와 역할만 바뀌므로 다시 실행해도 된다.

  ```bash
  cd test/jmeter
  export LOAD_PASSWORD='<학생 비밀번호>' PROFESSOR_PASSWORD='<교수 비밀번호>'
  ./accounts.sh 100 > /tmp/accounts.sql
  LOAD_PASSWORD="$PROFESSOR_PASSWORD" ./accounts.sh 1 loadprof PROFESSOR >> /tmp/accounts.sql
  # 로컬 DB. DB 비밀번호는 환경 변수 MYSQL_PWD로 넘긴다(-p만 쓰면 터미널이 없어 SQL 첫 줄을 비밀번호로 읽고 실패한다)
  export MYSQL_PWD='<DB 비밀번호>'
  docker exec -i -e MYSQL_PWD sahmhoot-mysql mysql -u<DB 사용자> sahmhoot < /tmp/accounts.sql
  ```

  `-e MYSQL_PWD`처럼 값 없이 쓰면 현재 셸의 값을 넘기므로 비밀번호가 명령 인자(`ps`)에 보이지 않는다. 비밀번호는 저장소·PR에 쓰지 않는다. 실제 사용자 계정이 있는 DB에 넣을 때는 아래 [실행 뒤 정리](#실행-뒤-정리)를 함께 한다.

## 실행

스크립트 경로가 실행 위치 기준이므로 **`test/jmeter`에서 실행한다.**

```bash
cd test/jmeter
export PROFESSOR_PASSWORD='<교수 비밀번호>' LOAD_PASSWORD='<학생 계정 비밀번호>'
jmeter -n -t sahmhoot-quiz.jmx \
  -Jusers=30 -Jrampup=6 \
  -JprofessorEmail=loadprof1@load.test \
  -JmetricsFile=/tmp/load/metrics.csv -JrunsFile=/tmp/load/runs.csv \
  -l /tmp/load/result.jtl -e -o /tmp/load/report
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
| `runsFile` | `runs.csv` | 실행마다 "방 ID,문제 세트 ID"를 한 줄씩 덧붙이는 파일. `cleanup.sh`가 읽는다 |

비밀번호는 환경 변수 `PROFESSOR_PASSWORD`(교수), `LOAD_PASSWORD`(학생, `accounts.sh`와 같은 값)로만 받는다. JMeter는 `-J` 속성 값을 `jmeter.log`에 그대로 남기므로 비밀번호를 `-J`로 넘기지 않는다.

서버 자원은 서버가 실행 중인 PC에서 기록한다.

```bash
./monitor.sh "$(lsof -t -iTCP:8080 -sTCP:LISTEN)" server.csv   # PID를 포트로 찾는다. java -jar로 띄운 서버도 찾는다
```

CPU는 직전 표본 이후 1초 구간의 사용률이다(코어 하나 = 100%). Linux는 `/proc/<PID>/stat`의 CPU 시간 증가분을 실제 경과 시간으로 나누고, macOS는 `top -l`의 값을 쓴다. `ps -o %cpu`는 Linux에서 프로세스 시작 뒤 누적 평균, macOS에서 감쇠 평균이라 짧은 부하에서 낮게 잡히므로 쓰지 않는다. 서버가 다른 사용자로 실행 중이면 `lsof`·`/proc` 읽기에 `sudo`가 필요할 수 있다.

## 결과 보기

- 콘솔 마지막 `summary =` 줄의 `Err`가 0%여야 한다. 실패가 있으면 `result.jtl`의 `responseMessage`에 이유가 있다
- `-e -o`로 만든 HTML 보고서에서 단계별 응답 시간을 본다. `문항 열림 수신`·`문항 마감 수신`·`결과 수신`·`접속 인원 확인`·`응답 수·결과 확인`은 메시지를 **기다린 시간**이라 서버 응답 시간이 아니다
- 서버 처리 지연은 `metricsFile`의 지표로 본다(표본과 섞지 않으려고 따로 모은다). 머리글 다음 행의 `failed_samples`가 0이 아니면 그 실행의 지표는 쓰지 않는다

| 지표 | 정의 |
| --- | --- |
| `start_to_first_open_ms` | 퀴즈 시작 요청 직전 → 학생이 첫 QUESTION_OPENED를 받은 시각 |
| `transition_ms(nextOpensAt→OPENED)` | 직전 QUESTION_CLOSED의 `nextOpensAt`(다음 문항 예정 시각) → 학생이 다음 QUESTION_OPENED를 받은 시각. 문항 전환 지연(NFR-02) |
| `answer_to_progress_ms` | k번째로 보낸 답의 전송 시각 → 교수가 응답 수 k 이상을 담은 ANSWER_PROGRESS를 받은 시각. 교수 화면의 답 반영 지연(NFR-02 "답 반영 1초"). 서버는 응답 현황을 최소 500ms 간격으로 묶어 보내므로 그 대기도 들어간다 |
| `last_answer_to_closed_ms` | 그 문항의 마지막 답을 보낸 시각 → 학생이 QUESTION_CLOSED를 받은 시각. 몰린 답 전체의 처리 + 조기 마감 + 전달 시간이라, 답을 보낸 시각이 얼마나 몰렸는지에 따라 크게 달라진다. NFR-02의 답 반영 지연과 같은 값이 아니다 |
| `delivery_ms(...)` | 메시지 `sentAt`(서버가 메시지를 만든 시각) → 학생이 받은 시각. 서버 발행·전달 지연 |

수신 시각은 WebSocket 리스너가 프레임을 받은 순간에 기록한다. `transition`·`delivery`는 서버 시각과 JMeter PC 시각을 비교하므로, 원격 서버에서는 두 시계의 차이가 그대로 더해진다(측정 전 NTP 동기 상태를 기록한다). `answer_to_progress_ms`·`last_answer_to_closed_ms`는 JMeter 시각끼리 비교한다.

100명 측정(#48)에서는 지표와 함께 서버의 WebSocket 인바운드 처리 스레드 수를 기록한다. `WebSocketConfig`에서 따로 정하지 않아 Spring 기본값(CPU 코어 수 × 2)이다. 답 하나를 처리할 때 방 잠금 안에서 쿼리가 약 10개 실행되므로 인원이 늘면 답 처리 시간이 길어진다.

## 데이터

- 실행마다 부하 전용 교수 계정에 PRIVATE 문제 세트 1개와 방 1개가 생기고, 방은 정리 단계에서 종료된다. 참여·답안 기록은 남는다
- 문제 세트 삭제 API는 퀴즈에 쓰인 세트를 지우지 않으므로(QUESTION_SET_IN_USE) `cleanup.sh`로 지운다

## 실행 뒤 정리

배포 서버처럼 실제 사용자 계정이 있는 DB에서 실행했다면 측정이 끝난 뒤 아래를 한다. 로컬 DB도 같은 방법으로 정리할 수 있다.

1. 부하 계정 비밀번호를 무작위 값으로 바꾼다. 비밀번호를 아는 인증 완료 계정이 남지 않게 한다

   ```bash
   LOAD_PASSWORD="$(openssl rand -base64 24)" ./accounts.sh 100 > /tmp/lock.sql
   LOAD_PASSWORD="$(openssl rand -base64 24)" ./accounts.sh 1 loadprof PROFESSOR >> /tmp/lock.sql
   docker exec -i -e MYSQL_PWD sahmhoot-mysql mysql -u<DB 사용자> sahmhoot < /tmp/lock.sql
   ```

   계정 수·접두사는 실행 때 만든 것과 같게 한다(더 많이 만든 적이 있으면 그 수로). 서버에는 토큰을 무효화하는 기능이 없어, 실행 중 발급된 토큰은 만료(14일)까지 유효하다. 토큰은 JMeter 메모리에만 있고 결과 파일(`result.jtl`, 보고서)에는 응답 본문이 남지 않는다.
2. 실행에서 만든 방·문제 세트와 그 기록을 지운다

   ```bash
   ./cleanup.sh /tmp/load/runs.csv > /tmp/cleanup.sql
   docker exec -i -e MYSQL_PWD sahmhoot-mysql mysql -u<DB 사용자> sahmhoot < /tmp/cleanup.sql
   ```

   `runs.csv`에 있는 방·세트 중 `@load.test` 계정이 만든 것만, 방은 종료된 것만 지운다. 수업 기록 보존 규칙의 예외로 부하 테스트 데이터에만 쓴다. 한 트랜잭션으로 실행되며, 외래 키 오류(목록 밖의 방이 쓴 세트 등)가 나면 아무것도 지우지 않고 멈춘다. 지운 뒤 `runs.csv`는 비운다.

학생·교수 계정 자체는 다음 측정에서 다시 쓰므로 지우지 않는다.

## 사전 점검 결과 (2026-11-15, 로컬)

환경: 서버·JMeter·MySQL·Redis를 같은 PC(Apple M5 10코어, 16GB, Java 21)에서 실행. develop `239998d`(#28·#89 포함), `local` 프로필. 문항 2개, 제한시간 20초, 학생 전원 즉시 응답. 학생은 수업 화면과 같은 채널을 구독하고 상태를 조회한다. 10명·30명 각 2회.

| 항목 | 10명 1회 | 10명 2회 | 30명 1회 | 30명 2회 |
| --- | --- | --- | --- | --- |
| 표본·실패 | 116·0 | 116·0 | 336·0 | 336·0 |
| 로그인 평균/최대(ms) | 84/88 | 86/90 | 84/91 | 84/89 |
| 입장 평균/최대(ms) | 10/21 | 9/10 | 8/10 | 8/10 |
| 연결·구독·RECEIPT 평균/최대(ms) | 10/15 | 9/10 | 8/13 | 8/12 |
| 상태 조회 평균/최대(ms) | 10/29 | 8/12 | 7/14 | 7/14 |
| 시작 → 첫 문항 수신 평균(ms) | 26 | 15 | 14 | 16 |
| 문항 전환 지연 평균/최대(ms) | 37/39 | 14/14 | 20/33 | 22/23 |
| 답 → 교수 응답 현황 평균/최대(ms) | 367/559 | 328/525 | 432/521 | 432/524 |
| 마지막 답 → 마감 수신 평균/최대(ms) | 188/190 | 130/165 | 338/399 | 342/395 |
| 전달 지연 QUESTION_OPENED 평균/최대(ms) | 8/13 | 4/6 | 7/23 | 5/8 |
| 서버 CPU 최대·RSS 최대 | 기록 안 함 | 45%·325MB | 45%·349MB | 45%·355MB |

- 모든 실행에서 교수 응답 현황 최댓값과 최종 결과의 문항별 응답 수가 학생 수와 같았다
- **마지막 답 → 마감 수신이 인원에 비례해 늘었다**(10명 약 130~190ms, 30명 약 340ms). 답 저장이 방 잠금 안에서 순서대로 처리되기 때문으로 보인다. 같은 비율이면 100명에서 1초에 가까워지므로 #48에서 우선 확인한다
- 답 → 교수 응답 현황은 서버가 응답 현황을 500ms 간격으로 묶어 보내는 대기가 대부분이다(최대 약 520~560ms)
- 로그인 약 85ms는 BCrypt 비용이 대부분이다. 100명이 동시에 로그인하면 CPU 사용이 몰릴 수 있어 #48에서 ramp-up을 함께 기록한다
- 같은 PC에서 실행해 네트워크 지연이 없고, JMeter가 서버와 CPU를 나눠 썼다. 배포 서버 측정(#48)은 JMeter를 다른 PC에서 실행한다
- 실패 처리 확인(10명): 학생 1명이 첫 답 제출에서 실패하도록 넣었을 때, 그 연결을 닫으면 나머지 학생의 마지막 답 → 마감 수신이 최대 168ms였고, 닫지 않으면 약 21.7초(제한시간 마감)였다. 두 실행 모두 `failed_samples`가 2(실패한 학생, 응답 수 9/10으로 실패한 교수 확인)였다. 학생 비밀번호가 틀리면 교수가 준비 대기 중 바로 실패해 실행이 3초 안에 끝났다
- 측정하지 않은 경로: 일부 미응답(제한시간 마감), 답 수정, 마감 뒤 늦은 답, 진행 중 연결 끊김·재연결. 채팅·이모지 전송은 기능 완성 뒤 시나리오에 추가한다
