// QUIZ_FINISHED를 기다린 뒤 연결을 닫는다.
Class Stomp = props.get('sahm.Stomp')
def stomp = vars.getObject('stomp')
long timeout = 15_000
def frame = stomp.await(timeout) { it.command == 'MESSAGE' && it.type == 'QUIZ_FINISHED' }
Stomp.metric(props, 'delivery_ms(QUIZ_FINISHED)', frame.receivedAt - Stomp.millis(frame.json.sentAt))
stomp.disconnect()
