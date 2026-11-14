// 답 제출. 학생에게 성공 응답은 없다. 반영 여부는 교수의 응답 현황과 최종 결과로 확인한다.
def stomp = vars.getObject('stomp')
List<String> choices = vars.get('choiceIds').split(',') as List
String choice = choices[new Random().nextInt(choices.size())]
String runQuestionId = vars.get('runQuestionId')
long sentAt = System.currentTimeMillis()
stomp.sendJson("/app/rooms/${props.get('sahm.roomId')}/answer".toString(),
    "{\"runQuestionId\":${runQuestionId},\"choiceId\":${choice}}")
props.get('sahm.lastAnswerAt').merge(runQuestionId, sentAt, { a, b -> Math.max(a, b) })
props.get('sahm.answerSentAt').computeIfAbsent(runQuestionId, { new java.util.concurrent.ConcurrentLinkedQueue<Long>() }).add(sentAt)
