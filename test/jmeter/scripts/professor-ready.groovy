// 학생 users명이 모두 준비(구독 RECEIPT 수신·state 조회 성공)될 때까지 기다린 뒤, 접속 인원이 users명인지 확인한다.
// RECEIPT는 구독이 브로커에 등록된 뒤에 오므로, 준비가 끝난 학생은 첫 문항 열림을 놓치지 않는다.
import groovy.json.JsonSlurper
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
Class Stomp = props.get('sahm.Stomp')
int users = props.getProperty('users', '10') as int
long deadline = System.currentTimeMillis() + (props.getProperty('readyTimeoutSeconds', '120') as long) * 1000
def ready = props.get('sahm.ready')
def failures = props.get('sahm.failures')
while (ready.get() < users) {
  // 학생이 하나라도 실패하면 전원 응답 조기 마감이 되지 않으므로 기다리지 않고 실패한다.
  if (failures.get() > 0) throw new IllegalStateException("실패한 표본 ${failures.get()}개, 준비 ${ready.get()}/${users}")
  if (System.currentTimeMillis() > deadline) throw new IllegalStateException("준비 ${ready.get()}/${users}에서 시간 초과")
  Thread.sleep(100)
}
// 접속 집계는 구독 등록 전에 끝나므로 이 시점에는 users명이어야 한다(교수는 세지 않는다).
URI uri = URI.create("${props.get('sahm.base')}/api/rooms/${props.get('sahm.roomId')}/state")
def request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
    .header('Authorization', "Bearer ${props.get('sahm.profToken')}").GET().build()
HttpResponse<String> res = Stomp.HTTP.send(request, HttpResponse.BodyHandlers.ofString())
if (res.statusCode() != 200) throw new IllegalStateException("수업 상태 조회 ${res.statusCode()} ${res.body()}")
int online = new JsonSlurper().parseText(res.body()).onlineCount
if (online != users) throw new IllegalStateException("준비 ${users}명인데 접속 인원 ${online}")
SampleResult.setResponseData("ready=${ready.get()} onlineCount=${online}", 'UTF-8')
