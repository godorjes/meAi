#!/usr/bin/env bash
# 把本机的 v2 数据快照还原到已安装的 Polyglot，用于验证“覆盖升级不丢数据”。
#
# 为什么需要它：Gradle 的 connectedDebugAndroidTest 在跑完后会卸载应用，
# 应用私有数据会随之删除。本脚本用事先取得的一致性快照把数据放回去，
# 再让应用自己完成 v2 → v3 的迁移，从而在真实数据上验证升级路径。
#
# 只做还原，不清库、不 wipe-data、不改包名与签名。

set -euo pipefail

SDK=/f/android-sdk
ADB="$SDK/platform-tools/adb.exe"
export MSYS_NO_PATHCONV=1

PKG=app.polyglot.dev
BACKUP_DIR="$(dirname "$0")/../docs/change-backups/data-baseline-20260915"
SNAPSHOT="$BACKUP_DIR/polyglot-v2-snapshot.db"
LEGACY_PREFS="$BACKUP_DIR/polyglot_secure-legacy.xml"

[ -f "$SNAPSHOT" ] || { echo "缺少快照：$SNAPSHOT" >&2; exit 1; }

APPDIR="/data/data/$PKG"
"$ADB" shell "ls $APPDIR" >/dev/null || { echo "应用未安装：$PKG" >&2; exit 1; }

UID_APP=$("$ADB" shell "stat -c %u $APPDIR" | tr -d '\r')
echo "应用 uid = $UID_APP"

# 停掉应用，避免还原过程中有进程持有数据库
"$ADB" shell "am force-stop $PKG"

# 数据库：连 -wal/-shm 一起清掉，再放回单文件一致性快照
"$ADB" shell "mkdir -p $APPDIR/databases $APPDIR/shared_prefs"
"$ADB" shell "rm -f $APPDIR/databases/polyglot.db $APPDIR/databases/polyglot.db-wal $APPDIR/databases/polyglot.db-shm"
"$ADB" push "$SNAPSHOT" /data/local/tmp/restore.db >/dev/null
"$ADB" shell "cp /data/local/tmp/restore.db $APPDIR/databases/polyglot.db && rm -f /data/local/tmp/restore.db"

# 历史密钥密文：adb shell cat 会把换行变成 CRLF，写回前必须还原成 LF，
# 否则 XML 里的密文会被插入 \r，字节变了就再也解不开。
# 中转文件用仓库内的相对路径：MSYS_NO_PATHCONV=1 时 adb 不认 /tmp 这类 Unix 路径。
if [ -f "$LEGACY_PREFS" ]; then
  TMP_PREFS="$BACKUP_DIR/polyglot_secure-lf.xml"
  tr -d '\r' < "$LEGACY_PREFS" > "$TMP_PREFS"
  "$ADB" push "$TMP_PREFS" /data/local/tmp/polyglot_secure.xml >/dev/null
  "$ADB" shell "cp /data/local/tmp/polyglot_secure.xml $APPDIR/shared_prefs/polyglot_secure.xml && rm -f /data/local/tmp/polyglot_secure.xml"
  rm -f "$TMP_PREFS"
  echo "已还原历史密钥密文（换行已修正为 LF）"
fi

# 归属与 SELinux 上下文必须和普通应用数据一致，否则应用打不开
"$ADB" shell "chown -R $UID_APP:$UID_APP $APPDIR/databases $APPDIR/shared_prefs"
"$ADB" shell "chmod 700 $APPDIR/databases $APPDIR/shared_prefs"
"$ADB" shell "find $APPDIR/databases $APPDIR/shared_prefs -type f -exec chmod 600 {} +"
"$ADB" shell "restorecon -R $APPDIR/databases $APPDIR/shared_prefs"

echo "--- 还原后的库版本与计数 ---"
"$ADB" shell "sqlite3 $APPDIR/databases/polyglot.db 'PRAGMA user_version;'"
"$ADB" shell "sqlite3 $APPDIR/databases/polyglot.db 'SELECT (SELECT COUNT(*) FROM sessions),(SELECT COUNT(*) FROM messages),(SELECT COUNT(*) FROM providers),(SELECT COUNT(*) FROM catalog_models);'"

echo "还原完成。启动应用即会触发 v2 → v3 迁移。"
