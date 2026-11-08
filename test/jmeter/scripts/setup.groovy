// setUp: 교수 로그인 → 문제 세트·문항 생성 → 방 생성. 이후 스레드가 쓸 값을 props에 둔다.
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import org.apache.jmeter.services.FileServer

File dir = new File(FileServer.fileServer.baseDir)
Class Stomp = new GroovyClassLoader(getClass().classLoader).parseClass(new File(dir, 'Stomp.groovy'))
props.put('sahm.Stomp', Stomp)
props.put('sahm.metrics', new ConcurrentLinkedQueue())
props.put('sahm.lastAnswerAt', new ConcurrentHashMap())

String protocol = props.getProperty('protocol', 'http')
String host = props.getProperty('host', 'localhost')
String port = props.getProperty('port', '8080')
String base = "${protocol}://${host}:${port}"
props.put('sahm.base', base)
props.put('sahm.ws', "${protocol == 'https' ? 'wss' : 'ws'}://${host}:${port}/ws".toString())

def call = { String method, String path, Object body, String token ->
  // 응답이 오지 않는 서버에서 준비 단계가 끝없이 기다리지 않게 한다.
  def builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(java.time.Duration.ofSeconds(15))
      .header('Content-Type', 'application/json')
  if (token) builder.header('Authorization', "Bearer ${token}")
  builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
      : HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(body)))
  HttpResponse<String> res = Stomp.HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString())
  if (res.statusCode() >= 300) throw new IllegalStateException("${method} ${path} → ${res.statusCode()} ${res.body()}")
  res.body() ? new JsonSlurper().parseText(res.body()) : null
}

// 비밀번호는 -J 속성으로 받지 않는다(JMeter가 -J 값을 jmeter.log에 그대로 남긴다).
String email = props.getProperty('professorEmail')
String password = System.getenv('PROFESSOR_PASSWORD')
if (!email || !password) throw new IllegalStateException('-JprofessorEmail과 환경 변수 PROFESSOR_PASSWORD가 필요합니다')
if (!System.getenv('LOAD_PASSWORD')) throw new IllegalStateException('환경 변수 LOAD_PASSWORD(학생 계정 비밀번호)가 필요합니다')
String token = call('POST', '/api/auth/login', [email: email, password: password], null).accessToken
int seconds = props.getProperty('questionSeconds', '20') as int
long stamp = System.currentTimeMillis() % 100000
def set = call('POST', '/api/question-sets', [title: "부하점검 ${stamp}".toString(), visibility: 'PRIVATE'], token)
call('POST', "/api/question-sets/${set.id}/questions", [type: 'MULTIPLE_CHOICE', content: '부하 점검 객관식', timeLimitSeconds: seconds,
    choices: [[content: '1', isCorrect: true], [content: '2', isCorrect: false], [content: '3', isCorrect: false], [content: '4', isCorrect: false]]], token)
call('POST', "/api/question-sets/${set.id}/questions", [type: 'TRUE_FALSE', content: '부하 점검 OX', timeLimitSeconds: seconds,
    choices: [[content: 'O', isCorrect: true], [content: 'X', isCorrect: false]]], token)
def room = call('POST', '/api/rooms', [title: "부하점검 ${stamp}".toString()], token)

props.put('sahm.profToken', token)
props.put('sahm.setId', set.id.toString())
props.put('sahm.roomId', room.id.toString())
props.put('sahm.joinCode', room.joinCode)
props.put('sahm.questions', '2')
SampleResult.setResponseData("room=${room.id} set=${set.id} users=${props.getProperty('users', '10')}", 'UTF-8')
