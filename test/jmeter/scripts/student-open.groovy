// 다음 문항 QUESTION_OPENED를 기다린다. 첫 문항은 교수가 접속 인원을 확인한 뒤 시작하므로 대기가 길다.
Class Stomp = props.get('sahm.Stomp')
def stomp = vars.getObject('stomp')
int qno = (vars.get('qno') as int) + 1
long timeout = qno == 1 ? (props.getProperty('readyTimeoutSeconds', '120') as long) * 1000 + 30_000
    : (props.getProperty('questionSeconds', '20') as long) * 1000 + 15_000
def frame = stomp.await(timeout) { it.command == 'MESSAGE' && it.type == 'QUESTION_OPENED' && it.json.questionNo == qno }
def m = frame.json
Stomp.metric(props, 'delivery_ms(QUESTION_OPENED)', frame.receivedAt - Stomp.millis(m.sentAt))
if (qno == 1) {
  Stomp.metric(props, 'start_to_first_open_ms', frame.receivedAt - (props.get('sahm.startRequestedAt') as long))
} else {
  Stomp.metric(props, 'transition_ms(nextOpensAt→OPENED)', frame.receivedAt - Stomp.millis(vars.get('nextOpensAt')))
}
vars.put('qno', qno.toString())
vars.put('runQuestionId', m.runQuestionId.toString())
vars.put('choiceIds', m.choices*.id.join(','))
