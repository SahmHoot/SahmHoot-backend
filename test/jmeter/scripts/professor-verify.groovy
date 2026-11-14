// 퀴즈 종료까지 기다린 뒤, 문항마다 교수 응답 현황과 최종 결과의 응답 수가 users명인지 확인한다.
Class Stomp = props.get('sahm.Stomp')
def stomp = vars.getObject('stomp')
int users = props.getProperty('users', '10') as int
int questions = props.get('sahm.questions') as int
long timeout = questions * ((props.getProperty('questionSeconds', '20') as long) * 1000 + 15_000)
def finished = stomp.await(timeout) { it.command == 'MESSAGE' && it.type == 'QUIZ_FINISHED' }
def progress = stomp.drain { it.command == 'MESSAGE' && it.type == 'ANSWER_PROGRESS' }
Map<String, Integer> maxProgress = [:]
progress.each { maxProgress.merge(it.json.runQuestionId.toString(), it.json.answeredCount as int, { a, b -> Math.max(a, b) }) }
// 답 반영 지연: 응답 수 k를 담은 ANSWER_PROGRESS 수신 시각 - k번째로 보낸 답의 전송 시각. 두 시각 모두 JMeter 시계다.
// 서버는 응답 현황을 최소 간격(500ms)으로 묶어 보내므로 이 값에는 그 대기도 들어간다(교수 화면이 실제로 겪는 지연).
progress.each { frame ->
  def sent = props.get('sahm.answerSentAt').get(frame.json.runQuestionId.toString())
  int k = frame.json.answeredCount as int
  if (sent != null && k >= 1) {
    List<Long> times = (sent as List<Long>).sort()
    if (k <= times.size()) Stomp.metric(props, 'answer_to_progress_ms', frame.receivedAt - times[k - 1])
  }
}
List<String> problems = []
List questionsInResult = finished.json.result?.questions ?: []
if (questionsInResult*.questionNo.sort() != (1..questions).toList()) {
  problems << "결과 문항 ${questionsInResult*.questionNo}가 1~${questions}와 다름"
}
questionsInResult.each { q ->
  if (q.answeredCount != users) problems << "문항 ${q.questionNo} 결과 응답 ${q.answeredCount}/${users}"
  Integer seen = maxProgress[q.runQuestionId.toString()]
  if (seen != users) problems << "문항 ${q.questionNo} 응답 현황 최대 ${seen}/${users}"
}
stomp.disconnect()
SampleResult.setResponseData("progress=${maxProgress} result=${finished.json.result.questions*.answeredCount}", 'UTF-8')
if (problems) throw new IllegalStateException(problems.join('; '))
