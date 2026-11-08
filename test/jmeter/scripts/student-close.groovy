// QUESTION_CLOSED를 기다린다. 전원이 답하면 제한시간 전에 조기 마감된다(#28).
Class Stomp = props.get('sahm.Stomp')
def stomp = vars.getObject('stomp')
String runQuestionId = vars.get('runQuestionId')
long timeout = (props.getProperty('questionSeconds', '20') as long) * 1000 + 15_000
def frame = stomp.await(timeout) { it.command == 'MESSAGE' && it.type == 'QUESTION_CLOSED' && it.json.runQuestionId.toString() == runQuestionId }
def m = frame.json
Stomp.metric(props, 'delivery_ms(QUESTION_CLOSED)', frame.receivedAt - Stomp.millis(m.sentAt))
Long last = props.get('sahm.lastAnswerAt').get(runQuestionId)
if (last != null) Stomp.metric(props, 'last_answer_to_closed_ms', frame.receivedAt - last)
if (m.nextOpensAt) vars.put('nextOpensAt', m.nextOpensAt)
