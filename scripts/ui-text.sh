#!/usr/bin/env bash
# 读取当前界面上的真实文本与节点数，作为不依赖人眼的客观验收手段。
# 用 bash 的字符切片截断（不是按字节切），避免把中文切成乱码。
# 用法：bash scripts/ui-text.sh [截断字符数，0 表示不截断]

SDK=/f/android-sdk
ADB="$SDK/platform-tools/adb.exe"
export MSYS_NO_PATHCONV=1

GREP=/usr/bin/grep
[ -x "$GREP" ] || GREP=grep
MAX="${1:-60}"

"$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
"$ADB" shell cat /sdcard/ui.xml | tr -d '\r' > /tmp/ui.xml

clip() {
  local s="$1"
  if [ "$MAX" = "0" ]; then printf '%s' "$s"; else printf '%s' "${s:0:$MAX}"; fi
}

printf '节点数: %s\n' "$(tr '>' '\n' < /tmp/ui.xml | "$GREP" -c '<node ')"
echo "--- text ---"
while IFS= read -r line; do
  [ -z "$line" ] && continue
  clip "$line"
  echo
done < <(tr '>' '\n' < /tmp/ui.xml | sed -n 's/.*text="\([^"]*\)".*/\1/p')
echo "--- content-desc ---"
while IFS= read -r line; do
  [ -z "$line" ] && continue
  clip "$line"
  echo
done < <(tr '>' '\n' < /tmp/ui.xml | sed -n 's/.*content-desc="\([^"]*\)".*/\1/p')
