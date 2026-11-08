// 접속 인원이 users명이 될 때까지 기다린다(학생은 퀴즈 채널을 구독해야 접속으로 센다). 못 채우면 실패.
import groovy.json.JsonSlurper
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
Class Stomp = props.get('sahm.Stomp')
int users = props.getProperty('users', '10') as int
long deadline = System.currentTimeMillis() + (props.getProperty('readyTimeoutSeconds', '120') as long) * 1000
URI uri = URI.create("${props.get('sahm.base')}/api/rooms/${props.get('sahm.roomId')}/state")
int online = -1
while (System.currentTimeMillis() < deadline) {
  // 서버가 응답하지 않아도 제한시간을 넘겨 기다리지 않도록 요청마다 남은 시간 안에서 끊는다.
  long left = Math.max(1, Math.min(5_000, deadline - System.currentTimeMillis()))
  def request = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(left))
      .header('Authorization', "Bearer ${props.get('sahm.profToken')}").GET().build()
  HttpResponse<String> res
  try {
    res = Stomp.HTTP.send(request, HttpResponse.BodyHandlers.ofString())
  } catch (HttpTimeoutException ignored) {
    continue
  }
  if (res.statusCode() != 200) throw new IllegalStateException("수업 상태 조회 ${res.statusCode()} ${res.body()}")
  online = new JsonSlurper().parseText(res.body()).onlineCount
  if (online >= users) break
  Thread.sleep(250)
}
if (online < users) throw new IllegalStateException("접속 인원 ${online}/${users}에서 시간 초과")
// 구독이 접속으로 집계된 뒤 브로커 등록까지의 짧은 간격을 둔다.
Thread.sleep(1000)
SampleResult.setResponseData("onlineCount=${online}", 'UTF-8')
