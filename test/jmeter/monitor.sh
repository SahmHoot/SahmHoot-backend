#!/usr/bin/env bash
# 프로세스의 CPU·메모리(RSS)를 1초마다 CSV로 기록한다. Ctrl+C로 멈춘다.
#
#   ./monitor.sh <PID> [출력 파일]
#   PID 찾기(포트 기준, java -jar로 띄운 서버도 찾음): lsof -t -iTCP:8080 -sTCP:LISTEN
#
# CPU는 직전 표본 이후 구간의 사용률(코어 하나 = 100%). ps -o %cpu는 누적·감쇠 평균이라 짧은 부하에서 낮게 잡혀 쓰지 않는다.
#   - Linux: /proc/<PID>/stat의 CPU 시간(utime+stime) 증가분 / 실제 경과 시간(/proc/uptime)
#   - macOS: top -l의 구간 사용률(첫 표본은 기준이 없어 버린다)
# RSS는 KB. RSS는 JVM 힙 사용량과 다르다.
set -euo pipefail

pid="${1:?사용법: $0 <PID> [출력 파일]}"
out="${2:-monitor.csv}"
kill -0 "$pid" 2>/dev/null || { echo "PID ${pid} 프로세스가 없습니다" >&2; exit 1; }
echo "time,cpu_percent,rss_kb" > "$out"

case "$(uname -s)" in
  Linux)
    hz="$(getconf CLK_TCK)"
    # 2번째 필드(프로세스 이름)에 공백이 있을 수 있어 마지막 ')' 뒤부터 센다. utime·stime은 그 뒤 12·13번째.
    ticks() { sed 's/.*) //' "/proc/${pid}/stat" | awk '{print $12 + $13}'; }
    uptime_s() { awk '{print $1}' /proc/uptime; }
    prev_ticks="$(ticks)"
    prev_time="$(uptime_s)"
    while sleep 1; do
      now_ticks="$(ticks 2>/dev/null)" || break
      now_time="$(uptime_s)"
      rss="$(awk '/^VmRSS:/ {print $2}' "/proc/${pid}/status" 2>/dev/null)" || break
      cpu="$(awk -v t="$((now_ticks - prev_ticks))" -v hz="$hz" -v d="$(awk -v a="$now_time" -v b="$prev_time" 'BEGIN {print a - b}')" \
        'BEGIN {printf "%.1f", (d > 0 ? t / hz / d * 100 : 0)}')"
      echo "$(date +%H:%M:%S),${cpu},${rss}" >> "$out"
      prev_ticks="$now_ticks"
      prev_time="$now_time"
    done
    ;;
  Darwin)
    top -l 0 -s 1 -pid "$pid" -stats pid,cpu | awk -v pid="$pid" '$1 == pid {print $2; fflush()}' | {
      read -r _ || exit 0
      while read -r cpu; do
        rss="$(ps -o rss= -p "$pid" | tr -d ' ')"
        [[ -n "$rss" ]] || break
        echo "$(date +%H:%M:%S),${cpu},${rss}" >> "$out"
      done
    }
    ;;
  *)
    echo "Linux·macOS만 지원합니다" >&2
    exit 1
    ;;
esac
