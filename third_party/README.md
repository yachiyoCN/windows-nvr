# 第三方组件

| 组件 | 版本／用途 | 来源 | 许可资料 |
| --- | --- | --- | --- |
| FFmpeg | Windows 发布包使用 9.0.2 essentials，负责拉流、录制、回放和音频处理 | https://www.gyan.dev/ffmpeg/builds/ | `FFmpeg/LICENSE.txt`、`FFmpeg/README.txt` |
| YOLOX-tiny | 0.1.1rc0 发布的 COCO 人形模型 | https://github.com/Megvii-BaseDetection/YOLOX | `person-detection/LICENSE-YOLOX.txt` |
| ONNX Runtime Java | 1.20.0，Windows x64 CPU 推理 | https://github.com/microsoft/onnxruntime | `person-detection/LICENSE-ONNXRuntime.txt` |
| OpenJDK 运行环境 | 构建时所选 JDK 的 `jlink` 输出 | 所选 JDK 发行方 | 打包运行环境中的 `legal/` |

模型和推理运行库的固定下载地址与 SHA-256 见 `person-detection/NOTICE.txt`。下载脚本包含同一校验值，校验失败时不会用下载文件覆盖已有依赖。

仓库保存第三方许可文本和构建资料，不包含这些组件的二进制文件。

根目录的 Apache License 2.0 适用于项目自有代码，不更改这些组件的许可证。FFmpeg 通过独立进程调用；随发布包分发其可执行文件时，须保留 GPLv3 许可和版权通知，并按 GPLv3 提供该构建对应的完整源码，包括其构建配置及适用的外部库源码。上游提交链接是来源线索，不能单独代替对应源码的分发要求。

ONNX Runtime JAR 应完整保留，包括包内的 `ThirdPartyNotices.txt`。打包 OpenJDK 运行环境时须保留所选发行版的 `legal/` 文件。YOLOX 模型和 ONNX Runtime 的来源通知见 `person-detection/NOTICE.txt`。

