// 학생 WebSocket 연결·CONNECT, 퀴즈 채널·오류 큐 구독.
Class Stomp = props.get('sahm.Stomp')
String roomId = props.get('sahm.roomId')
def stomp = Stomp.connect(props.get('sahm.ws'), vars.get('token'), 10_000)
stomp.subscribe('/user/queue/errors')
stomp.subscribe("/topic/rooms/${roomId}/quiz".toString())
vars.putObject('stomp', stomp)
vars.put('qno', '0')
