// tearDown: 방 종료, 지표 요약을 로그·파일로 남긴다.
import java.net.http.HttpRequest
import java.net.http.HttpResponse
Class Stomp = props.get('sahm.Stomp')
if (Stomp == null) return
// 방 종료가 실패해도 지표 요약은 남기고, 이 표본을 실패로 표시한다.
String closeProblem = null
try {
  def request = HttpRequest.newBuilder(URI.create("${props.get('sahm.base')}/api/rooms/${props.get('sahm.roomId')}/close"))
      .timeout(java.time.Duration.ofSeconds(10))
      .header('Authorization', "Bearer ${props.get('sahm.profToken')}").method('PATCH', HttpRequest.BodyPublishers.noBody()).build()
  HttpResponse<String> res = Stomp.HTTP.send(request, HttpResponse.BodyHandlers.ofString())
  if (res.statusCode() >= 300) closeProblem = "방 종료 ${res.statusCode()} ${res.body()}"
} catch (Exception e) {
  closeProblem = "방 종료 실패: ${e}"
}
Map<String, List<Long>> byName = [:].withDefault { [] }
props.get('sahm.metrics').each { byName[it[0]] << (it[1] as long) }
def pct = { List<Long> xs, double p -> xs[Math.min(xs.size() - 1, Math.ceil(p * xs.size()) as int - 1)] }
List<String> lines = ['metric,count,avg_ms,p50_ms,p95_ms,max_ms']
byName.sort().each { name, values ->
  values.sort()
  lines << [name, values.size(), String.format('%.1f', values.sum() / values.size()), pct(values, 0.5), pct(values, 0.95), values[-1]].join(',')
}
String summary = lines.join('\n')
log.info("\n${summary}")
println(summary)
File out = new File(props.getProperty('metricsFile', 'metrics.csv'))
out.text = summary + '\n'
SampleResult.setResponseData(summary, 'UTF-8')
if (closeProblem) {
  log.warn(closeProblem)
  SampleResult.setSuccessful(false)
  SampleResult.setResponseMessage(closeProblem)
}
