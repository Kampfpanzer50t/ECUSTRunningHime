# 华理跑步姬 iPhone 源码

原生 iOS **2.0.6** 工程位于 `ios/Huati.xcodeproj`，最低 iOS 16，支持 iPhone 8 / 8 Plus / X 及更新兼容机型。此源码副本不含 IPA、苹果签名或真实登录会话。

上传仓库后可手动执行 **Actions → Build iPhone IPA**。该流程使用 macOS 编译并测试，再生成 ARM64 未签名 IPA；下载构建附件后，用自己的有效苹果签名工具签名安装。也可在 Mac 使用 Xcode 打开工程、选择自己的 Team 后运行。

手动时长支持分钟、秒、毫秒，定位无效或校外时自动选择校内模拟起点；开始前自动刷新缺失或过期三点。iOS 本机锁屏后台由系统调度，不能保证准点；可选电脑代执行仍需要电脑持续联网且保持运行。

详细构建、签名、地图及功能说明见 [ios/README.md](ios/README.md)，项目目录与上传方式见 [README.md](README.md)。
