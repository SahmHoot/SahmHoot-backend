// tearDown: 방 종료, 이번 실행의 방·문제 세트 ID 기록, 지표 요약을 로그·파일로 남긴다.
import java.net.http.HttpRequest
import java.net.http.HttpResponse
Class Stomp = props.get('sahm.Stomp')
String roomId = props.get('sahm.roomId')
if (Stomp == null || roomId == null) return
// 방 종료가 실패해도 지표 요약은 남기고, 이 표본을 실패로 표시한다.
String closeProblem = null
try {
  def request = HttpRequest.newBuilder(URI.create("${props.get('sahm.base')}/api/rooms/${roomId}/close"))
      .timeout(java.time.Duration.ofSeconds(10))
      .header('Authorization', "Bearer ${props.get('sahm.profToken')}").method('PATCH', HttpRequest.BodyPublishers.noBody()).build()
  HttpResponse<String> res = Stomp.HTTP.send(request, HttpResponse.BodyHandlers.ofString())
  if (res.statusCode() >= 300) closeProblem = "방 종료 ${res.statusCode()} ${res.body()}"
} catch (Exception e) {
  closeProblem = "방 종료 실패: ${e}"
}
// cleanup.sh가 이 실행에서 만든 것만 지우도록 방·문제 세트 ID를 쌓아 둔다.
new File(props.getProperty('runsFile', 'runs.csv')).append("${roomId},${props.get('sahm.setId')}\n")

// 이 표본(방 종료)의 실패는 파일을 쓴 뒤에야 실패 처리기가 세므로 여기서 미리 더한다.
int failed = props.get('sahm.failures').get() + (closeProblem ? 1 : 0)
Map<String, List<Long>> byName = [:].withDefault { [] }
props.get('sahm.metrics').each { byName[it[0]] << (it[1] as long) }
def pct = { List<Long> xs, double p -> xs[Math.min(xs.size() - 1, Math.ceil(p * xs.size()) as int - 1)] }
List<String> lines = ['metric,count,avg_ms,p50_ms,p95_ms,max_ms']
// 실패한 표본이 있으면 남은 학생이 제한시간까지 기다리는 등 지표가 실제와 달라지므로, 이 행으로 실행 전체를 구분한다.
lines << "failed_samples,${failed},,,,"
byName.sort().each { name, values ->
  values.sort()
  lines << [name, values.size(), String.format('%.1f', values.sum() / values.size()), pct(values, 0.5), pct(values, 0.95), values[-1]].join(',')
}
String summary = lines.join('\n')
log.info("\n${summary}")
println(summary)
if (failed > 0) {
  String warning = "실패한 표본 ${failed}개: 이 실행의 지표는 결과로 쓰지 않는다"
  log.warn(warning)
  println(warning)
}
File out = new File(props.getProperty('metricsFile', 'metrics.csv'))
out.text = summary + '\n'
SampleResult.setResponseData(summary, 'UTF-8')
if (closeProblem) {
  log.warn(closeProblem)
  SampleResult.setSuccessful(false)
  SampleResult.setResponseMessage(closeProblem)
}
