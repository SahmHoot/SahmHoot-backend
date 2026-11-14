// 교수 WebSocket 연결. 교수 수업 화면과 같은 채널(응답 현황 stats 큐 포함)을 구독하고 RECEIPT를 기다린다.
Class Stomp = props.get('sahm.Stomp')
String roomId = props.get('sahm.roomId')
def stomp = Stomp.connect(props.get('sahm.ws'), props.get('sahm.profToken'), 10_000)
vars.putObject('stomp', stomp)
stomp.subscribeRoom(roomId, true, 10_000)
