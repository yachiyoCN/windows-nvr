# Windows监控录像机

Windows监控录像机是一套面向家庭和小型场所的本地网络录像机（NVR）。项目包含 Windows 桌面端、内置 Web 管理界面和 Android 客户端，支持多路视频预览、连续录像、时间轴回放、人员事件标记及远程访问。

当前版本：Windows 桌面端 1.4.1，Android 客户端 1.5.1。

## 主要功能

- 管理最多 16 路 RTSP、RTSPS 或 HTTPS 视频源
- 单画面、四分屏和九分屏实时预览
- 多通道统一录制、容量上限和保留天数管理
- 连续时间轴、跨片段回放、日期区间检索和 0.5～8 倍速播放
- 基于 YOLOX 与 ONNX Runtime 的本地人员检测和事件标记
- 每个通道独立设置画质、连接方式和录像时间水印
- HTTPS Web 管理界面及带摄像头权限控制的 Android 客户端
- 本地账号、加密会话、证书指纹校验和登录审计

人员检测在录像机本地运行，不会将监控画面发送到云端服务。

## 运行环境

- Windows 10/11 x64
- 支持 TCP/IP 的摄像头或视频源
- FFmpeg、FFprobe 和 FFplay
- 可选：YOLOX-tiny 模型与 ONNX Runtime Java 1.20.0
- Android 客户端要求 Android 8.0（API 26）或更高版本

## 快速开始

1. 运行打包目录中的 `录像机.exe`。
2. 在“添加设备”中填写视频源地址和认证信息。
3. 在“录像机设置”中选择保存目录、容量上限和保留天数。
4. 点击“全部开始录像”启动已配置通道的录制。
5. 如需远程访问，在“远程访问”中创建用户、分配通道权限并启用对应服务。

详细操作见 [使用说明](docs/使用说明.md)。

## 构建

桌面端使用 JDK 21 或更高版本构建：

```powershell
.\build.ps1 -CompileOnly
```

完整便携版还需要 FFmpeg。依赖齐备后执行：

```powershell
.\build.ps1 -DependencyDirectory 'D:\recorder-dependencies' -IncludePersonDetection
```

Android 客户端需要 JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0。完整构建参数、正式签名和更新包制作方式见 [开发说明](docs/开发说明.md)。

## 项目结构

| 路径 | 内容 |
| --- | --- |
| `desktop/src/main/java/` | Windows 桌面端 Java 源码 |
| `desktop/src/main/resources/` | 桌面端图标和内置 Web 资源 |
| `desktop/native/` | Windows 录像目录校验组件 |
| `desktop/assets/` | 应用图标源文件 |
| `android/` | Android Gradle 工程与构建脚本 |
| `scripts/` | 依赖准备、图标生成和更新打包工具 |
| `docs/` | 使用手册、开发文档和 API 合同 |
| `third_party/` | 第三方组件来源与许可证 |

## 安全

Web 和 App API 默认关闭，启用后仅接受 HTTPS。请使用高强度密码，核对客户端显示的证书指纹，并通过防火墙、VPN 或可信反向代理限制公网访问。不要提交运行配置、录像、证书私钥、签名文件或连接导出文件。

安全问题请通过 `yachi8000@yachiyo.cn` 私下报告；公开反馈中不要附带账号、密码、令牌、证书私钥或监控画面。

## 文档

- [使用说明](docs/使用说明.md)
- [开发说明](docs/开发说明.md)
- [App API](docs/API.md)
- [OpenAPI 定义](docs/openapi.yaml)
- [第三方组件](third_party/README.md)

## 作者

千夏Tesni

- QQ：2758399324
- 邮箱：yachi8000@yachiyo.cn

## 许可

项目暂未声明开源许可证。除非另有书面授权，项目代码保留全部权利。第三方组件遵循各自的许可证，详见 [第三方组件](third_party/README.md)。
