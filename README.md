# 鸣潮画质助手（Wuthering Visuals）

鸣潮画质助手是一款面向 Android 游戏鸣潮的虚幻配置替换工具，应用不会解析或改写引擎配置内容，只通过 Root 或 Shizuku 执行 Shell 文件操作，将内置的预设复制到鸣潮的配置目录，并提供备份、恢复和方向控制等辅助功能。

- 应用包名：`com.wuwa.config.manager`
- 当前版本：`v1.0.3`（`versionCode 103`）
- 最低系统：Android 7.0（API 24）
- 目标系统：Android 16（API 36）
- 界面：Kotlin + Jetpack Compose + Material 3（MD3，支持 Material You 动态取色）
- 核心逻辑：Java

## 重要：画质预设仅用于测试
仓库中的四档画质预设仅包含用于验证文件替换结果的测试标记，不代表正式画质参数，请自行配置。

| 档位 | 目录 |
| --- | --- |
| 低 | `app/src/main/assets/presets/low/` |
| 中 | `app/src/main/assets/presets/medium/` |
| 高 | `app/src/main/assets/presets/high/` |
| 极致 | `app/src/main/assets/presets/extreme/` |

编辑完成后重新构建应用即可

## 主要功能
- 支持多游戏服务器切换
- 支持shizuku和root
- 已实现手动备份、自动备份和恢复。
- 支持恢复应用内置的游戏原配置。
- 支持自动备份保留策略、日志导出与分享。
- 支持竖屏启动、悬浮方向控制和异常退出后的方向恢复。
- 支持浅色、深色、跟随系统及 Material You 动态取色。
- 支持中文和英文界面。
- 冷启动与手动触发的公告、更新检测。

## 支持的游戏版本

| 服务器 | 游戏包名 |
| --- | --- |
| 国服 | `com.kurogame.mingchao` |
| 哔哩哔哩服 | `com.kurogame.mingchao.bilibili` |
| 国际服 | `com.kurogame.wutheringwaves.global` |

## 权限与隐私

- 文件访问依赖 Root 或 Shizuku，不申请 `MANAGE_EXTERNAL_STORAGE`。
- 网络仅用于读取公开的公告和版本更新配置；更新安装包由外部网盘分发。
- 本公开仓库不包含服务器部署数据、后端管理工具、管理凭据、签名证书或私钥。

## 项目结构

```text
app/                 Android 应用源码与资源
  src/main/java/      核心逻辑（Java）
  src/main/kotlin/    界面（Kotlin + Compose + Material 3）
gradle/libs.versions.toml   版本目录（参照 KernelSU）
build.gradle.kts     根项目构建配置
settings.gradle.kts  项目设置
```

## 构建环境

- JDK 17 或 21
- Android SDK 36
- Android Gradle Plugin 8.13.2
- Gradle 9.5.1
- Kotlin 2.2.20 + Compose（Material 3）

仅进行源码检查与测试：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

构建安装包：

```powershell
.\gradlew.bat :app:assembleDebug
```


## 参与开发

欢迎提交 Issue 与 Pull Request。修改文件操作、备份或方向控制逻辑时，请同时补充相应测试，并优先验证 Android 7、Android 11、Android 13、Android 15 和 Android 16 的行为。

## 开源许可

本项目采用 [Apache License 2.0](LICENSE)。第三方组件的许可信息可在应用“关于与支持 → 开放源代码许可”中查看。

## 项目地址

[https://github.com/Droprains-hub/wuthering-visuals](https://github.com/Droprains-hub/wuthering-visuals)
