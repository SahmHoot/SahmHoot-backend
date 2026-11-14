// 수업 상태 조회. 프론트는 연결할 때마다 구독 RECEIPT를 받은 뒤 state를 조회한다(06 1절). 성공하면 준비 완료로 센다.
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
Class Stomp = props.get('sahm.Stomp')
URI uri = URI.create("${props.get('sahm.base')}/api/rooms/${props.get('sahm.roomId')}/state")
def request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
    .header('Authorization', "Bearer ${vars.get('token')}").GET().build()
HttpResponse<String> res = Stomp.HTTP.send(request, HttpResponse.BodyHandlers.ofString())
if (res.statusCode() != 200) throw new IllegalStateException("수업 상태 조회 ${res.statusCode()} ${res.body()}")
SampleResult.setResponseCode('200')
props.get('sahm.ready').incrementAndGet()
