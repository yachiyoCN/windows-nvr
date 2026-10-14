# 第三方组件

| 组件 | 版本／用途 | 来源 | 许可资料 |
| --- | --- | --- | --- |
| FFmpeg | Windows 发布包使用 9.0.2 essentials，负责拉流、录制、回放和音频处理 | https://www.gyan.dev/ffmpeg/builds/ | `FFmpeg/LICENSE.txt`、`FFmpeg/README.txt` |
| YOLOX-tiny | 0.1.1rc0 发布的 COCO 人形模型 | https://github.com/Megvii-BaseDetection/YOLOX | `person-detection/LICENSE-YOLOX.txt` |
| ONNX Runtime Java | 1.20.0，Windows x64 CPU 推理 | https://github.com/microsoft/onnxruntime | `person-detection/LICENSE-ONNXRuntime.txt` |
| OpenJDK 运行环境 | 构建时所选 JDK 的 `jlink` 输出 | 所选 JDK 发行方 | 打包运行环境中的 `legal/` |

模型和推理运行库的固定下载地址与 SHA-256 见 `person-detection/NOTICE.txt`。下载脚本包含同一校验值，校验失败时不会用下载文件覆盖已有依赖。

仓库保存第三方许可文本和构建资料，不包含这些组件的二进制文件。

