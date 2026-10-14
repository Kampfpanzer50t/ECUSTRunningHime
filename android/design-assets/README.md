# 界面素材与字体

v2.0.4 使用统一的雾蓝配色、淡蓝立体几何壁纸、浅色半透明功能卡片、模块内 Q 版角色与离线地图。主页面和登录页以固定屏幕尺寸的 `ArtImageView` 作为底层背景，背景图不调暗、不降低图片自身透明度，不参与滚动内容高度计算。主界面没有背景人物立绘或深蓝渐变遮罩；本轮从用户文件夹重新导入的 19 张人物插画仅供随机 3 秒开屏使用。

`ModuleCard` 在标题旁以固定 74.4×81.6 dp 显示透明人物，是此前 62×68 dp 的 1.2 倍；人物与可换行标题各占一列，列间距 12 dp，标题区底部留白 6 dp。页面两侧间距由 20 改为 16 dp，卡片上下留白扩大。卡片白色底为 alpha 210/255（约 82% 不透明度），文字和按钮仍以正常不透明度显示。主界面 12 个模块、登录页 2 个模块均使用该组件。旋转保留美术种子，切页按种子更换背景，每秒进度刷新只在运行状态变化时换人物姿态。

底栏每项最小 82 dp 高，上下各 8 dp 留白，正常字号约 98 dp；图标槽 56 dp 高。“模拟”显示桌面图标同一造型的跑步全身，“账号”显示普通姿态的完整头部，v2.0.4 将其显示框由 48 dp 缩小 20% 至 38.4 dp。人物不染色；其余三项由 `UiIcon` 绘制 28 dp 线条图标。标签为 12 sp，标签和图像共用一个带点击反馈的无障碍入口，放大字号时允许底栏自然增高。

`UiKit.copy/note` 统一说明文字为 13 sp、加大行距、深雾蓝文字与浅蓝提示底，使用短标题区分用途。展开说明有独立浅蓝边框与 48 dp 点击区域；段落自然换行。账号页隐藏美术资源介绍段，但保留非官方工具说明、地图和字体版权。

`CompanionDialog` 为创建记录、已设置锁屏自检、后台排查报告提供一致的头像、浅蓝标题区、分组内容及固定底部按钮。正文使用 ScrollView，长报告支持选择和复制；窗口宽度不超过 420 dp、高度不超过安全区域的 90%。横屏或大字号时改用紧凑标题和纵排参数。动作仍调用原有业务逻辑，不打开 App 或访问服务器来验证设计。

## 淡蓝几何壁纸

`render_geometry.py` 在 Blender 4.2.23 的 Cycles 渲染器中建立可复现的三维场景，制作三张 1080×2160 壁纸。云层为连续圆润形体，山体和丘陵采用不规则三角面，几何树形有倒角；加入瓷质微纹理、反光河道、柔和面光源和远景薄雾。`prepare_geometry.py` 调成统一的淡蓝色并转为 WebP，更新目录和来源校验信息，同时制作底栏人物。

形体词汇参考用户发来的 Kenney Background Elements 素材表；背景没有使用其 PNG、SVG、外部模型或摄影素材，全部几何体由脚本建立。便携渲染工具和 `.blend` 场景只留在电脑，不进入 APK；App 离线加载三张成品 WebP。已否定的自然照片不再打包。主界面 `backgrounds` 和开屏 `wallpapers` 使用独立目录。

在项目根目录重建（需本地 Blender、Python/Pillow/NumPy）：

```powershell
foreach ($geometryVariant in 0,1,2) {
    & .\.toolchain\blender\blender-4.2.23-windows-x64\blender.exe --background --python android/design-assets/render_geometry.py -- --variant $geometryVariant
}
python android/design-assets/prepare_geometry.py
```

## 用户提供的美术资源

原始图片来自 `E:/picture.ai.ecust.assistant/` 的 19 张 1024×1536 插画，以及 `E:/ai.q.ecust.as/` 六个服饰目录中的 36 张人物（18 组普通/跑步配对）。桌面图标指定使用 `01-原图截取6张/04-尴尬-跑步.png` 的头部，裁切像素范围为 `(80,100)–(286,291)`，保持比例并添加白色边距。原图不重绘、不修改。

`prepare_art.py` 将文件夹内 PNG、JPEG、WebP 插画转为无损 WebP，不再硬编码开屏图片数量。人物只移除与外部连接的近白背景，保留封闭区域的白色头发、服饰与脸部，按需缩至最大 768 像素。生成 `assets/art/catalog.json`、19 张开屏图和 36 张透明人物；源文件路径、哈希与尺寸保存在 `art-build.json`。桌面及界面图标位于 `res/drawable-nodpi`，Android 自适应图标支持圆形、圆角及单色主题。素材由用户提供，用于该用户指定的应用；它们不套用字体或地图的开源许可证。

运行环境需 Python 与 Pillow。在项目根目录生成：

```powershell
python android/design-assets/prepare_art.py --pictures E:/picture.ai.ecust.assistant --characters E:/ai.q.ecust.as
```

只更新头部图标可附加 `--icons-only`；只更新开屏可附加 `--wallpapers-only`，保留人物、导航头像和几何壁纸。App 运行不依赖这些 E 盘目录，全部资源随 APK 离线安装。v2.0.4 安装包约 41.24 MB，来自实际展示的插画、人物与字体，不添加填充数据。

`ArtRepository` 在单一后台线程解码请求的图片，以视图尺寸采样，并用最多 24 MB、同时不超过可用堆上限八分之一的位图缓存；不会加载全部插画。开屏随机插画在图片就绪后显示 3 秒，保存已显示时间以处理旋转和后台恢复。`SplashTitleView` 在图片左侧叠加淡蓝渐变，两个竖行分别显示“华理跑步姬—”“跑步记录模拟”，单字保持正向，破折号竖向排布，小屏时按可用高度缩小。底部仍显示应用名称、随机 tips 和进度线。系统启动图标尽快退出，后台闹钟和通知直接使用原服务与记录入口。

`CompanionState` 只读取现有记录判断展示姿态，不修改记录或时钟。模拟进行中使用跑步图，暂停、结束与提交中使用普通图；随机造型在新启动时选定，状态刷新保留造型与表情配对。

`module_preview.py` 使用实际资源生成 [v2.0.4 界面设计预览](../../dist/ecust-running-girl-v2.0.4-preview.jpg)，展示几何背景、分组提示与缩小的账号头像。`welcome_dialog_preview.py` 生成 [开屏与三个提示界面预览](../../dist/welcome-dialogs-v2.0.4-preview.jpg)。另有 [壁纸预览](../../dist/geometry-wallpapers-v2.0.3-preview.jpg)。这些是桌面素材与设计检查，非 Android 截图，不能用于确认手机上实际测量和排版效果；排查报告和参数均为示例。

## 中文字体

`UiKit` 为主界面和登录表单加载 `assets/fonts/campus-sans.ttf`。Campus Sans 是 Noto Sans SC 的静态常规字重子集，修改字体名称，按 SIL Open Font License 1.1 分发，许可证随 APK 保存在 `assets/fonts/OFL.txt`。原字体版权声明保留。

来源：Google Fonts 的 [Noto Sans SC](https://github.com/google/fonts/tree/main/ofl/notosanssc)。本目录保留原始可变字体 `NotoSansSC-source.ttf`、生成的静态源 `NotoSansSC-Regular-source.ttf` 和原许可证 `OFL-NotoSansSC.txt`；这些源文件不会打包进 App。字体包含 GB2312 常用汉字、界面与地图实际文字、拉丁字母和标点，剩余预算补充 GBK 汉字。未覆盖的罕见字符可由 Android 系统字体回退显示。

在项目根目录重新生成：

```powershell
.\.venv\Scripts\python.exe -m pip install fonttools==4.60.1
.\.venv\Scripts\python.exe android/design-assets/build_font.py
```

默认字体预算为 3,000,000 字节；可用 `--target-bytes` 调整。输出字符数量和实际大小写入 `font-build.json`。TTF 不经 ZIP 压缩，便于读取；APK 体积来自使用中的字体和地图资源，没有空白填充数据。修改界面文字后应先重新生成字体，再构建 APK。

校园地图来源和许可见 [../campus-data/README.md](../campus-data/README.md)。桌面设计或地图预览仅用于检查素材与排版，不是 Android 真机截图。
