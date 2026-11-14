// 학생 WebSocket 연결·CONNECT, 수업 화면과 같은 채널 구독, 구독 RECEIPT 대기.
Class Stomp = props.get('sahm.Stomp')
String roomId = props.get('sahm.roomId')
def stomp = Stomp.connect(props.get('sahm.ws'), vars.get('token'), 10_000)
// 구독 중에 실패해도 실패 처리기가 연결을 닫을 수 있게 먼저 둔다.
vars.putObject('stomp', stomp)
vars.put('qno', '0')
stomp.subscribeRoom(roomId, false, 10_000)
