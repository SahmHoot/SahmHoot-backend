#!/usr/bin/env bash
# 프로세스의 CPU·메모리(RSS)를 1초마다 CSV로 기록한다. Ctrl+C로 멈춘다.
#
#   ./monitor.sh <PID> [출력 파일]      예) ./monitor.sh "$(pgrep -f SahmhootApplication)" server.csv
#
# CPU는 ps 기준(코어 하나 = 100%), RSS는 KB. RSS는 JVM 힙 사용량과 다르다.
set -euo pipefail

pid="${1:?사용법: $0 <PID> [출력 파일]}"
out="${2:-monitor.csv}"
echo "time,cpu_percent,rss_kb" > "$out"
while kill -0 "$pid" 2>/dev/null; do
  echo "$(date +%H:%M:%S),$(ps -o %cpu= -o rss= -p "$pid" | awk '{print $1","$2}')" >> "$out"
  sleep 1
done
