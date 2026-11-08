// 교수 WebSocket 연결. 응답 현황(stats 큐)과 퀴즈 채널을 구독한다.
Class Stomp = props.get('sahm.Stomp')
String roomId = props.get('sahm.roomId')
def stomp = Stomp.connect(props.get('sahm.ws'), props.get('sahm.profToken'), 10_000)
stomp.subscribe('/user/queue/errors')
stomp.subscribe("/topic/rooms/${roomId}/quiz".toString())
stomp.subscribe("/user/queue/rooms/${roomId}/stats".toString())
vars.putObject('stomp', stomp)
