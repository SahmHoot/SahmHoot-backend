# 수업 방 생성 (#34)

로그인한 교수의 JWT로 `POST /api/rooms`를 호출한다.

```http
Authorization: Bearer <교수 accessToken>
Content-Type: application/json

{"title":"자료구조 3주차"}
```

성공하면 201과 `id`, `title`, `status: OPEN`, `joinCode`, `joinUrl`, `createdAt`을 반환한다.
수업명은 공백만 있는 값을 제외한 1~50자다. 학생은 403, 미로그인은 401이다.
방과 소유자의 참가 기록은 함께 저장되고, 소유자의 닉네임은 계정의 이름이다.
6자리 숫자 코드는 종료된 방까지 포함해 중복을 금지하고 충돌 시 최대 10번 시도한다.

`joinUrl`은 기본적으로 `http://localhost:5173/join/{joinCode}`다.
배포 시 환경변수 `APP_ROOM_JOIN_BASE_URL`을 프론트엔드의 공개 주소로 지정한다.
QR 이미지는 이 주소를 이용해 화면에서 생성한다.

테스트는 개발용 DB 대신 MySQL의 `sahmhoot_test` DB를 사용한다.

```powershell
.\gradlew.bat test --tests 'com.sahmhoot.room.RoomCreationIntegrationTest'
```

테스트 범위: 생성과 소유자 참여, JWT와 교수 권한, 수업명 검증, JSON 검증,
종료된 방과의 코드 충돌 재시도, 참가자 저장 실패 시 방 저장 취소.
