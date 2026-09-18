#!/usr/bin/env bash
# 列出当前界面每个节点的 文本 / bounds / clickable，用于精确点击与验收。
# 用 bash 的字符切片截断（不是按字节切），避免把中文切成乱码。
# 用法：bash scripts/ui-nodes.sh [过滤关键字] [截断字符数，默认 30]

SDK=/f/android-sdk
ADB="$SDK/platform-tools/adb.exe"
export MSYS_NO_PATHCONV=1

"$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
"$ADB" shell cat /sdcard/ui.xml | tr -d '\r' > /tmp/ui.xml

FILTER="${1:-}"
MAX="${2:-30}"
tr '>' '\n' < /tmp/ui.xml | sed -n 's/.*<node //p' | while IFS= read -r line; do
  text=$(printf '%s' "$line" | sed -n 's/.*text="\([^"]*\)".*/\1/p')
  desc=$(printf '%s' "$line" | sed -n 's/.*content-desc="\([^"]*\)".*/\1/p')
  bounds=$(printf '%s' "$line" | sed -n 's/.*bounds="\([^"]*\)".*/\1/p')
  click=$(printf '%s' "$line" | sed -n 's/.*clickable="\([^"]*\)".*/\1/p')
  [ -z "$text$desc" ] && continue
  if [ -n "$FILTER" ]; then
    case "$text$desc" in *"$FILTER"*) ;; *) continue ;; esac
  fi
  label="${text:-$desc}"
  label="${label:0:$MAX}"
  printf '%-*s | %-14s | clickable=%-5s | %s\n' "$MAX" "$label" "$bounds" "$click" "${desc:+desc:$desc}"
done
