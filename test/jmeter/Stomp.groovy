import groovy.json.JsonSlurper

import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 부하 시나리오용 최소 STOMP 1.2 클라이언트(JDK WebSocket). JMeter 스레드 하나가 연결 하나를 쓴다.
 *
 * - 수신 시각은 리스너가 프레임을 다 받은 순간 기록한다(큐 대기 시간과 분리)
 * - 조각난 텍스트는 마지막 조각까지 모아 처리하고, 한 메시지에 프레임이 여러 개면 NUL로 나눈다
 * - 하트비트는 0,0으로 협상해 주고받지 않는다
 * - 기다리는 프레임이 아닌 것은 보관해 두었다가 다음 대기에서 찾는다. 단, ignore()로 지정한 구독의 MESSAGE는 보관하지 않는다
 * - 구독마다 receipt 헤더를 붙인다. 서버는 구독이 브로커에 등록된 뒤 RECEIPT를 보낸다(SubscribeReceiptInterceptor).
 *   RECEIPT는 등록 완료만 뜻하고, 등록 전에 발행된 메시지를 다시 보내 주지는 않는다
 * - STOMP ERROR 프레임(CONNECT 거부 등), /user/queue/errors의 ERROR 메시지, 연결 종료는 즉시 실패로 던진다
 */
class Stomp {
  static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  static class Frame {
    String command
    Map<String, String> headers
    String body
    long receivedAt
    private Object parsed

    Object getJson() {
      if (parsed == null && body?.startsWith('{')) {
        parsed = new JsonSlurper().parseText(body)
      }
      parsed
    }

    String getType() {
      json instanceof Map ? json.type : null
    }

    String toString() {
      "${command} ${headers.destination ?: ''} ${body?.take(200) ?: ''}"
    }
  }

  final LinkedBlockingQueue<Frame> inbox = new LinkedBlockingQueue<>()
  final List<Frame> kept = []
  final StringBuilder partial = new StringBuilder()
  WebSocket socket
  volatile String closedReason
  int nextSubscription = 0
  /** 아직 RECEIPT를 받지 못한 receipt ID. */
  final Set<String> pendingReceipts = new LinkedHashSet<>()
  /** 메시지를 보관하지 않을 구독 ID(오류 검사는 한다). */
  final Set<String> ignored = new HashSet<>()
  private boolean disconnected

  /**
   * 프론트 roomSync.ts의 roomDestinations와 같은 방 채널 목록. 수업 화면은 이 채널을 모두 구독한다.
   * 프론트가 바뀌면 함께 바꾼다.
   */
  static List<List<String>> roomDestinations(String roomId, boolean owner) {
    List<List<String>> destinations = [
        ['quiz', "/topic/rooms/${roomId}/quiz".toString()],
        ['chat', "/topic/rooms/${roomId}/chat".toString()],
        ['reaction', "/topic/rooms/${roomId}/reaction".toString()],
        ['presence', "/topic/rooms/${roomId}/presence".toString()],
        ['me', "/user/queue/rooms/${roomId}/me".toString()]]
    if (owner) {
      destinations << ['stats', "/user/queue/rooms/${roomId}/stats".toString()]
    }
    destinations
  }

  static Stomp connect(String url, String token, long timeoutMs) {
    Stomp stomp = new Stomp()
    stomp.socket = HTTP.newWebSocketBuilder()
        .connectTimeout(Duration.ofMillis(timeoutMs))
        .buildAsync(URI.create(url), stomp.listener())
        .get(timeoutMs, TimeUnit.MILLISECONDS)
    try {
      stomp.send('CONNECT', [
          'accept-version': '1.2',
          'host'          : URI.create(url).host,
          'heart-beat'    : '0,0',
          'Authorization' : "Bearer ${token}".toString()], '', timeoutMs)
      stomp.await(timeoutMs) { it.command == 'CONNECTED' }
    } catch (Exception e) {
      // 호출한 쪽은 객체를 받지 못해 닫을 수 없으므로 여기서 닫는다.
      stomp.socket.abort()
      throw e
    }
    stomp
  }

  private WebSocket.Listener listener() {
    Stomp self = this
    new WebSocket.Listener() {
      @Override
      void onOpen(WebSocket webSocket) {
        webSocket.request(1)
      }

      @Override
      CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        self.partial.append(data)
        if (last) {
          self.parse(self.partial.toString(), System.currentTimeMillis())
          self.partial.setLength(0)
        }
        webSocket.request(1)
        null
      }

      @Override
      CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        self.closedReason = "연결 종료 ${statusCode} ${reason}".toString()
        self.inbox.add(new Frame(command: 'CLOSED', headers: [:], body: self.closedReason))
        null
      }

      @Override
      void onError(WebSocket webSocket, Throwable error) {
        self.closedReason = "연결 오류 ${error}".toString()
        self.inbox.add(new Frame(command: 'CLOSED', headers: [:], body: self.closedReason))
      }
    }
  }

  private void parse(String text, long receivedAt) {
    for (String raw : text.split('\u0000')) {
      String chunk = raw.replaceFirst(/^[\r\n]+/, '')
      if (chunk.isEmpty()) {
        continue
      }
      int split = chunk.indexOf('\n\n')
      String head = split < 0 ? chunk : chunk.substring(0, split)
      String body = split < 0 ? '' : chunk.substring(split + 2)
      List<String> lines = head.readLines()
      Map<String, String> headers = [:]
      lines.drop(1).each { line ->
        int colon = line.indexOf(':')
        if (colon > 0 && !headers.containsKey(line.substring(0, colon))) {
          headers[line.substring(0, colon)] = line.substring(colon + 1)
        }
      }
      inbox.add(new Frame(command: lines[0], headers: headers, body: body, receivedAt: receivedAt))
    }
  }

  void send(String command, Map<String, String> headers, String body, long timeoutMs = 10_000) {
    StringBuilder frame = new StringBuilder(command).append('\n')
    headers.each { k, v -> frame.append(k).append(':').append(v).append('\n') }
    frame.append('\n').append(body).append('\u0000')
    socket.sendText(frame.toString(), true).get(timeoutMs, TimeUnit.MILLISECONDS)
  }

  /** 구독하고 구독 ID를 돌려준다. receipt ID는 "r-{구독 ID}"이고 awaitReceipts()로 기다린다. */
  String subscribe(String destination) {
    String id = "sub-${nextSubscription++}".toString()
    String receipt = "r-${id}".toString()
    pendingReceipts.add(receipt)
    send('SUBSCRIBE', [id: id, destination: destination, receipt: receipt], '')
    id
  }

  /**
   * 수업 화면처럼 오류 큐와 방 채널을 모두 구독하고 RECEIPT를 기다린다. 시나리오가 쓰는 quiz·stats 외 채널의 메시지는 보관하지 않는다.
   * 구독 수와 서버의 발행 대상(채팅·반응·접속 인원)을 실제 화면과 같게 하려는 것이다.
   */
  void subscribeRoom(String roomId, boolean owner, long timeoutMs) {
    subscribe('/user/queue/errors')
    roomDestinations(roomId, owner).each { channel, destination ->
      String id = subscribe(destination)
      if (!(channel in ['quiz', 'stats'])) {
        ignore(id)
      }
    }
    awaitReceipts(timeoutMs)
  }

  /** 이 구독으로 오는 MESSAGE는 보관하지 않는다. 쓰지 않는 채널의 메시지가 쌓여 찾기가 느려지지 않게 한다. */
  void ignore(String subscriptionId) {
    ignored.add(subscriptionId)
  }

  /** 보낸 구독의 RECEIPT를 모두 받을 때까지 기다린다. 기다리는 동안 온 다른 프레임은 보관한다. */
  void awaitReceipts(long timeoutMs) {
    long deadline = System.currentTimeMillis() + timeoutMs
    while (!pendingReceipts.isEmpty()) {
      long left = deadline - System.currentTimeMillis()
      if (left <= 0) {
        throw new IllegalStateException("RECEIPT ${pendingReceipts.size()}개를 ${timeoutMs}ms 안에 받지 못함 ${pendingReceipts}")
      }
      Frame receipt = await(left) { it.command == 'RECEIPT' && pendingReceipts.contains(it.headers['receipt-id']) }
      pendingReceipts.remove(receipt.headers['receipt-id'])
    }
  }

  void sendJson(String destination, String json) {
    send('SEND', [destination: destination, 'content-type': 'application/json'], json)
  }

  /** 조건에 맞는 프레임을 기다린다. 맞지 않는 프레임은 보관한다. 시간이 지나면 실패로 던진다. */
  Frame await(long timeoutMs, Closure<Boolean> match) {
    Iterator<Frame> it = kept.iterator()
    while (it.hasNext()) {
      Frame frame = it.next()
      if (match(frame)) {
        it.remove()
        return frame
      }
    }
    long deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
      long left = deadline - System.currentTimeMillis()
      Frame frame = left > 0 ? inbox.poll(left, TimeUnit.MILLISECONDS) : null
      if (frame == null) {
        throw new IllegalStateException("${timeoutMs}ms 안에 기다리던 프레임이 오지 않음. 보관 ${kept.size()}개")
      }
      failOn(frame)
      if (match(frame)) {
        return frame
      }
      keep(frame)
    }
  }

  private void keep(Frame frame) {
    if (frame.command == 'MESSAGE' && ignored.contains(frame.headers.subscription)) {
      return
    }
    kept.add(frame)
  }

  /** 지금까지 받은 프레임 중 조건에 맞는 것을 모두 꺼낸다(기다리지 않음). */
  List<Frame> drain(Closure<Boolean> match) {
    Frame frame
    while ((frame = inbox.poll()) != null) {
      failOn(frame)
      keep(frame)
    }
    List<Frame> found = kept.findAll(match)
    kept.removeAll(found)
    found
  }

  private static void failOn(Frame frame) {
    if (frame.command == 'CLOSED') {
      throw new IllegalStateException(frame.body)
    }
    if (frame.command == 'ERROR') {
      throw new IllegalStateException("STOMP ERROR ${frame.headers.message}")
    }
    if (frame.command == 'MESSAGE' && frame.type == 'ERROR') {
      throw new IllegalStateException("서버 ERROR ${frame.json.code}: ${frame.json.message}")
    }
  }

  /** 연결을 닫는다. 여러 번 불러도 한 번만 닫는다. */
  void disconnect() {
    if (disconnected) {
      return
    }
    disconnected = true
    try {
      send('DISCONNECT', [:], '', 3_000)
      socket.sendClose(WebSocket.NORMAL_CLOSURE, '').get(3, TimeUnit.SECONDS)
    } catch (Exception ignored) {
      socket?.abort()
    }
  }

  /** 지표 하나를 기록한다. 표본(sample)과 섞지 않으려고 따로 모아 마지막에 요약한다. */
  static void metric(Properties props, String name, long value) {
    ((ConcurrentLinkedQueue) props.get('sahm.metrics')).add([name, value])
  }

  /** ISO-8601 시각을 밀리초로. 서버는 마이크로초까지 보내지만 지표는 밀리초 단위로 본다. */
  static long millis(String iso) {
    java.time.Instant.parse(iso).toEpochMilli()
  }
}
