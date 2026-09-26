# 家即副本 (HomeDungeonAR) - Agent 开发手册

## 1. 项目架构与模块划分

- `:core-logic` (纯 Kotlin / JVM 模块):
  - 承载规则怪谈状态机、剧本解析器、可解性校验器、槽位匹配算法。
  - **原则**：绝不依赖 Android SDK，全部通过 `./gradlew :core-logic:test` 跑 JUnit 单元测试。
- `:app` (Android 原生外壳模块):
  - 承载相机采集、AR 追踪外壳（M1 ARCore/EasyAR）、OpenGL ES 滤镜渲染、传感器与音频调度。
  - 基准测试设备：vivo S30（无 LiDAR、中端高通/联发科平台）。

## 2. 核心构建命令

```bash
# 1. 运行纯逻辑单元测试 (Agent 自测自修)
./gradlew :core-logic:test

# 2. 编译打包 Debug APK
./gradlew :app:assembleDebug

# 3. 安装到真机并启动
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.homedungeon.ar/.MainActivity

# 4. 查看应用运行时日志
adb logcat -s HomeDungeonAR:* AndroidRuntime:*
```

## 3. 本地环境配置

- JDK 17: `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`
- Android SDK: `/opt/homebrew/share/android-commandlinetools`
- 已内置阿里云 Maven 镜像与 Gradle 8.7 Wrapper，直接运行 `./gradlew` 即可构建。
