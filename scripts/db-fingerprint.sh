#!/usr/bin/env bash
# 对设备上的 polyglot.db 计算内容指纹，用于升级前后逐项比对。
# 只输出指纹与计数，不输出聊天正文或密钥。
#
# 用法：bash scripts/db-fingerprint.sh [db-path]
# 默认读取正在使用的数据库；传入快照路径可对快照计算。

set -u

SDK=/f/android-sdk
ADB="$SDK/platform-tools/adb.exe"
export MSYS_NO_PATHCONV=1

DB="${1:-/data/data/app.polyglot.dev/databases/polyglot.db}"

# 每个表挑选语义上必须保留的列，按主键排序，保证 dump 稳定可比
q() { "$ADB" shell "sqlite3 $DB \"$1\"" 2>/dev/null | tr -d '\r'; }

echo "== db: $DB =="
echo "user_version: $(q 'PRAGMA user_version;')"

# 表 -> 参与指纹的列。room_master_table / android_metadata 属于 Room 元数据，不参与内容比对。
fp() {
  local table="$1" cols="$2" order="$3"
  local rows
  rows=$(q "SELECT $cols FROM $table ORDER BY $order;")
  local n
  n=$(q "SELECT COUNT(*) FROM $table;")
  # sqlite3 把 NULL 打成空串，用分隔符避免相邻列歧义
  local sum
  sum=$(printf '%s' "$rows" | sha256sum | cut -c1-16)
  printf '%-14s rows=%-5s sha=%s\n' "$table" "$n" "$sum"
}

fp providers      "id||'|'||name||'|'||providerType||'|'||apiType||'|'||baseUrl||'|'||modelIdsJson||'|'||selectedModelId||'|'||createdAt||'|'||updatedAt" "id"
fp sessions       "id||'|'||title||'|'||IFNULL(configId,'-')||'|'||modelId||'|'||modelNameSnapshot||'|'||IFNULL(sourceSessionId,'-')||'|'||IFNULL(sourceTitle,'-')||'|'||createdAt||'|'||updatedAt||'|'||IFNULL(deleteUntil,'-')" "id"
fp messages       "id||'|'||sessionId||'|'||role||'|'||seq||'|'||content||'|'||status||'|'||IFNULL(requestId,'-')||'|'||IFNULL(errorKind,'-')||'|'||createdAt" "id"
fp drafts         "sessionId||'|'||text" "sessionId"
fp catalog_models "providerType||'|'||modelId||'|'||desc" "providerType,modelId"

# 消息正文合计长度，用于快速判断正文有没有被截断
echo "messages content length total: $(q 'SELECT IFNULL(SUM(LENGTH(content)),0) FROM messages;')"
echo "sessions with null configId  : $(q 'SELECT COUNT(*) FROM sessions WHERE configId IS NULL;')"
