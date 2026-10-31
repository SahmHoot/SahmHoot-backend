# 수업 목록·종료 (#35)

- `GET /api/rooms/mine`: 교수 본인의 OPEN·PLAYING 수업을 생성 시각과 ID 내림차순으로 반환한다.
  CLOSED는 제외하고 접속 인원은 #45 구현 전까지 0이다.
- `PATCH /api/rooms/{roomId}/close`: 방 소유자만 실행할 수 있다. 성공은 본문 없는 204,
  비소유자는 403, 없는 방은 404 ROOM_NOT_FOUND다. 같은 방을 다시 종료해도 204다.
- 종료 시 CLOSED·초 단위 closed_at을 저장한다. 진행 중인 최신 퀴즈 회차와 열린 문항은
  기존 퀴즈 중단 기능으로 ABORTED·CLOSED 처리하며 참가자·문항·선택지·답 기록은 삭제하지 않는다.
- 공용 RoomLockManager 잠금 → 별도 트랜잭션 → 커밋 → 예약 취소 훅 → ROOM_ENDED 발행 → 잠금 해제 순서다.
  롤백되면 취소 훅과 알림은 실행되지 않는다.
- 현재 퀴즈 스케줄러는 미구현 상태다. 스케줄러 담당자는 `RoomTaskCanceller` 빈을 구현해
  방별 모든 예약을 취소하도록 연결해야 한다. 트랜잭션 밖, 방 잠금 안에서 동기 호출된다.
  취소 훅의 RuntimeException은 로그만 남기고 다음 훅과 ROOM_ENDED 발행은 계속된다.
- 종료 알림 발행이 실패해도 로그만 남기고 204를 반환한다. 이미 커밋된 CLOSED 상태는 유지된다.
  알림은 자동 재전송하지 않으며, 같은 방의 종료를 다시 요청해도 취소 훅과 알림을 재실행하지 않는다.

로컬 MySQL의 sahmhoot_test DB를 켠 뒤 실행한다.

```powershell
.\gradlew.bat test --tests 'com.sahmhoot.room.RoomManagementIntegrationTest'
.\gradlew.bat test
```
