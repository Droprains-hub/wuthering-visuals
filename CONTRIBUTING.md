# 参与贡献

感谢参与鸣潮画质助手的开发。

## 开始之前

1. 使用 JDK 17 与 Android SDK 36。
2. 不要提交签名证书、私钥、服务器凭据、本机路径或用户日志。
3. `app/src/main/assets/presets/` 中的文件是测试预设；如提交真实参数，请说明适用的游戏版本、设备范围与测试结果。
4. 文件操作必须继续通过 Root 或 Shizuku Shell 执行，不要改为直接访问 `Android/data` 的 Java 文件操作。

## 提交前检查

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

请同时检查中文与英文资源、不同屏幕宽度、浅色与深色主题，以及 Root/Shizuku 两条权限路径。

## Pull Request

- 清楚描述问题、修改内容和验证方法。
- 界面修改请附截图。
- 权限、备份、恢复、方向控制等高风险修改请附可复现步骤。
