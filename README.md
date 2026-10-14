# 华理跑步姬

安卓、iPhone / iPad 和 Windows 桌面版源代码，附 Python 命令行与抓包工具。此目录是可独立上传的源码副本，未包含登录会话、抓包记录、签名材料、本机运行数据、安装包或开发工具缓存。

当前版本：安卓 **2.0.5**、iOS **2.0.6**、Windows **2.1.1**。

应用包含登录与会话导入、路线模拟、每日计划、暂停/继续/舍弃、记录查询及校园地图。手动时长支持分钟、秒和毫秒，总时长为 1–120 分钟。定位失败、坐标无效或校外时自动选择校内模拟起点；开始前刷新缺失或过期的三点。模拟数据不代表实际运动，记录有效性由服务器判断。配置与保存的计时精确到毫秒，实际唤醒和提交仍受系统及网络调度影响。

## 目录

| 目录或文件 | 内容 |
| --- | --- |
| `android/` | 安卓 Java 应用、Gradle 配置、地图、美术资源及单元测试 |
| `ios/` | SwiftUI 应用、Xcode 工程、共享 JS 规则、电脑代执行工具及测试 |
| `desktop/` | Electron 桌面应用、界面、图标、依赖锁定文件及测试 |
| `tests/` | Python 工具测试 |
| `.github/workflows/` | 手动触发的 Android、iOS、Windows 构建流程 |
| 根目录 `.py`、`.ps1` | Python 协议工具、抓包工具和本地环境/构建脚本 |

## 上传 GitHub

推荐用 GitHub Desktop 添加这个目录，然后发布到自己的仓库。完整文件夹及资源可以通过 Git 上传；网页拖拽不适合一次上传全部文件。

也可在本目录使用 Git：

```sh
git init -b main
git add .
git commit -m "Initial source release"
git remote add origin https://github.com/YOUR_NAME/YOUR_REPOSITORY.git
git push -u origin main
```

替换仓库地址即可。上传的是本目录的内容；`.github` 必须位于仓库根目录，不能额外嵌套一层文件夹。源码内已带 `.gitignore`，排除账号、密钥、缓存及安装包。

## GitHub 构建

上传后进入 **Actions**，手动运行所需流程：

- **Build Android APK**：生成未签名 APK；发布安装包时使用自己的安卓签名。
- **Build iPhone IPA**：使用 macOS 编译，执行规则与原生测试，生成 ARM64 未签名 IPA。安装到 iPhone 仍需有效苹果签名。
- **Build Windows EXE**：生成 Windows x64 便携 EXE。

这些流程只在手动触发时运行，不包含账号登录或真实跑步提交。

## 本地运行 Windows 版

需要 Node.js 22 或更新兼容版本和 pnpm 10。

```sh
cd desktop
pnpm install --frozen-lockfile
pnpm test
pnpm start
pnpm dist
```

安装依赖会下载 Electron；`stage` 自动从 `ios/` 准备桌面共享规则、地图和美术资源，不需要原电脑的缓存。EXE 输出到根目录 `dist/windows-v2.1.1/`。

## 本地构建 Android

需要 JDK 17、Gradle 8.9 和 Android SDK（platforms;android-35、build-tools;35.0.0）。可以在 Android Studio 打开 `android/`，配置 SDK 后构建；`android/local.properties` 由本机生成，不上传仓库。

```sh
gradle -p android testDebugUnitTest lintRelease assembleRelease
```

未签名 APK 位于 `android/app/build/outputs/apk/release/`。原电脑使用的签名密钥未复制，重新签名时使用自己的密钥；不同签名不能直接覆盖原安装包。

Windows 可运行 `python prepare_android_tools.py` 下载本地 JDK、Gradle 与 SDK 命令行工具。还需使用下载的 `sdkmanager.bat` 将 Android SDK 安装到 `.toolchain/sdk/` 并接受官方许可；之后根目录 `Build-Android.ps1` 会生成本地 `local.properties`，运行测试、构建并使用本地签名。没有密钥时会在 `private/` 创建新签名，该目录已忽略。

## 本地构建 iOS

Mac 上使用 Xcode 16 或更新版本打开 `ios/Huati.xcodeproj`。工程最低 iOS 16，支持 iPhone 8 / 8 Plus / X 及更新兼容机型。原生测试与未签名 IPA 构建：

```sh
cd ios
bash Verify-iOS.command
bash Build-Unsigned-IPA.command
```

IPA 输出到 `ios/Exports/Unsigned/`。签名与安装流程见 `ios/README.md`。iOS 锁屏后台唤醒由系统决定；可选电脑代执行需要自己的电脑持续联网运行。

共享规则测试不需要 Mac：

```sh
node --test ios/Tests/core.test.cjs ios/Tests/companion.test.cjs
```

Windows 的 Swift 语法/工程资源检查需要 Node.js、Python 和 `requirements-source-checks.txt` 中的依赖：

```sh
python -m pip install -r requirements-source-checks.txt
python ios/tools/verify_project.py
```

该脚本的本地语法检查不能替代 Xcode 原生编译。

## Python 工具

执行 `Setup.ps1` 安装独立抓包环境，或查看源码脚本的 `--help`。命令行工具默认读取自己的本地 `private/` 数据，导入文件需要自行准备；此副本没有任何真实账号会话。测试可用 `python -m unittest discover -s tests` 运行。

## 美术与地图来源

应用图标、Q 版人物及开屏插画由项目使用者提供；保留现有来源说明，不为这些资源新增开源许可。地图与道路数据来自 OpenStreetMap，字体遵循对应 OFL 许可。详见 `android/design-assets/README.md`、`android/campus-data/README.md` 及 `ios/Huati/Content/Licenses/`。应用构建所需的地图、图片、字体和图标均包含在本目录；美术重制脚本中引用的原始 E 盘插画文件需要自行提供，编译应用无需重制美术。

复制来源与文件校验摘要见 `source-export-manifest.json`。
