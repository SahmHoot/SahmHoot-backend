// 퀴즈 종료까지 기다린 뒤, 문항마다 교수 응답 현황과 최종 결과의 응답 수가 users명인지 확인한다.
def stomp = vars.getObject('stomp')
int users = props.getProperty('users', '10') as int
int questions = props.get('sahm.questions') as int
long timeout = questions * ((props.getProperty('questionSeconds', '20') as long) * 1000 + 15_000)
def finished = stomp.await(timeout) { it.command == 'MESSAGE' && it.type == 'QUIZ_FINISHED' }
def progress = stomp.drain { it.command == 'MESSAGE' && it.type == 'ANSWER_PROGRESS' }
Map<String, Integer> maxProgress = [:]
progress.each { maxProgress.merge(it.json.runQuestionId.toString(), it.json.answeredCount as int, { a, b -> Math.max(a, b) }) }
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
