# meAi / Polyglot

使用 Kotlin、Jetpack Compose 和 Room 构建的 Android AI 聊天应用，支持配置多个模型服务、流式回复、会话管理和换模型续聊。

## 本地构建

- JDK 17
- Android SDK 35
- 使用仓库内的 Gradle Wrapper

在本地创建 `local.properties` 并填写 Android SDK 路径，然后运行：

```sh
./gradlew assembleDebug
```

Windows 使用 `gradlew.bat assembleDebug`。生成的安装包位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 模型配置

聊天使用的 API Key 在应用内配置。应用可在没有额外 App 级 Key 的情况下使用预置模型目录。

如需启用每日模型列表查询，可将 `app/src/main/assets/app_provider_keys.example.json` 复制为同目录下的 `app_provider_keys.json`，再在本机填写对应凭证。此文件已被 Git 忽略，不得提交真实 Key。填写的资产文件会被打包进 APK，请勿分发包含私人 Key 的安装包。

## 测试

连接测试设备或模拟器后执行：

```sh
./gradlew connectedDebugAndroidTest
```

规格和历史实现说明见 `docs/`。本地数据库备份、界面截图、密钥和构建产物不纳入版本控制。
