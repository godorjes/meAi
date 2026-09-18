#!/usr/bin/env bash
# 为 F:\android-sdk 补齐 Android 模拟器运行环境（Windows + Git Bash，可重复执行）
#
# 背景：本机 Hyper-V / WHPX / VirtualMachinePlatform 均为 Disabled，
#       因此使用 AEHD（Android Emulator Hypervisor Driver）做硬件加速，无需重启。
#
# 用法：bash scripts/android-sdk-setup.sh
set -uo pipefail

JAVA_HOME="F:\\gradle\\jdk17"
JAVA="/f/gradle/jdk17/bin/java.exe"
SDK_WIN="F:\\android-sdk"
SDK_DIR="/f/android-sdk"
CLT_WIN="$SDK_WIN\\cmdline-tools\\latest"
AVD_NAME="polyglot_api35"
AVD_IMAGE="system-images;android-35;google_apis;x86_64"
AVD_DEVICE="pixel_9"
AEHD_DIR="$SDK_DIR/extras/google/Android_Emulator_Hypervisor_Driver"

sdkmanager() {
  MSYS_NO_PATHCONV=1 "$JAVA" -classpath "$CLT_WIN\\lib\\*" \
    com.android.sdklib.tool.sdkmanager.SdkManagerCli --sdk_root="$SDK_WIN" "$@"
}
avdmanager() {
  MSYS_NO_PATHCONV=1 "$JAVA" "-Dcom.android.sdkmanager.toolsdir=$CLT_WIN" \
    -classpath "$CLT_WIN\\lib\\avdmanager-classpath.jar" \
    com.android.sdklib.tool.AvdManagerCli "$@"
}

step() { echo; echo "### $*"; }

step "1/5 检查 JDK（需 17+）"
"$JAVA" -version 2>&1 | head -1

step "2/5 检查 cmdline-tools"
if [ ! -x "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager.bat" ]; then
  echo "缺少 cmdline-tools，请手动解压到 $SDK_DIR/cmdline-tools/latest 后重跑"
  exit 1
fi
echo "cmdline-tools 已就位"

step "3/5 接受许可并安装 emulator / 系统镜像 / AEHD 驱动"
yes | sdkmanager --licenses >/dev/null 2>&1
yes | sdkmanager "emulator" "$AVD_IMAGE" "extras;google;Android_Emulator_Hypervisor_Driver" 2>&1 \
  | grep -viE "^\[|Loading local repository|Fetch remote repository|Computing updates" | tail -5

step "4/5 安装并启动 AEHD 驱动"
if sc query aehd 2>/dev/null | grep -q RUNNING; then
  echo "aehd 已在运行"
else
  ( cd "$AEHD_DIR" && MSYS_NO_PATHCONV=1 \
      RUNDLL32.EXE 'SETUPAPI.DLL,InstallHinfSection' DefaultInstall 132 '.\aehd.Inf' )
  sleep 5
  sc start aehd >/dev/null 2>&1
  sc query aehd | grep -E "STATE" || { echo "AEHD 启动失败"; exit 1; }
fi
"$SDK_DIR/emulator/emulator.exe" -accel-check 2>&1 | tail -2
# 卸载驱动：bash scripts/android-sdk-setup.sh --remove-driver
if [ "${1:-}" = "--remove-driver" ]; then
  sc stop aehd && sc delete aehd && echo "已卸载 aehd"
fi

step "5/5 创建 AVD（已存在则跳过）"
if avdmanager list avd 2>/dev/null | grep -q "Name: $AVD_NAME"; then
  echo "AVD $AVD_NAME 已存在"
else
  echo no | avdmanager create avd -n "$AVD_NAME" -k "$AVD_IMAGE" -d "$AVD_DEVICE" --force 2>&1 \
    | grep -viE "^\[|Loading local repository" | tail -3
fi
avdmanager list avd 2>/dev/null | grep -viE "^\[|Loading" | head -8

echo
echo "完成。日常使用："
echo "  启动模拟器: /f/android-sdk/emulator/emulator.exe -avd $AVD_NAME -no-boot-anim"
echo "  装并跑 APK: /f/android-sdk/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk"
echo "              /f/android-sdk/platform-tools/adb.exe shell am start -n app.polyglot.dev/.MainActivity"
echo "  截图:       /f/android-sdk/platform-tools/adb.exe exec-out screencap -p > shot.png"
