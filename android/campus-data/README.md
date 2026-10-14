# 奉贤校区范围与模拟起点

本版仅配置华东理工大学奉贤校区，与此项目此前使用的校区一致。

`fengxian-boundary.json` 保存 2026-10-12 获取的 WGS84 边界，来自 [OpenStreetMap way 304214471](https://www.openstreetmap.org/way/304214471)。地图数据 © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright)，按 [ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/) 提供。安卓代码 `CampusArea` 包含对应多边形，用于手机 WGS84 位置的校内判断；它不是华体服务器官方公布的校园范围。

随机模拟起点从 [Fang4321/Fuck-ECUST-Health 的 run.py](https://github.com/Fang4321/Fuck-ECUST-Health/blob/main/run.py) 列出的三个校内考核点中选择：单双杠练习场、考核点 38、体育馆主馆。接口点位坐标按 GCJ-02 使用；测试将候选点转换回 WGS84，验证均在上述校区边界内。

随机起点是模拟数据，不表示手机真实位置，也不表示服务器一定会将最终记录判为有效。若使用其他校区，需要同时更换边界和候选点。

## 离线校园道路图

`fengxian-roads.osm` 为 2026-10-12 从 OpenStreetMap 公共接口取得的原始 XML 地图数据：

https://api.openstreetmap.org/api/0.6/map?bbox=121.490,30.827,121.510,30.840

`build_road_graph.py` 提取校区边界内的步行道、路径、服务道路、居住道路、跑道等允许步行的道路，排除 `foot=no`，保留最大的连通分量。生成 `android/app/src/main/assets/campus-roads.json`：763 个节点、902 条边，WGS84 坐标。App 使用时转换为 GCJ-02，连接三个考核点，随后按实际路线长度生成采样与计时数据。它不是导航服务，不能判断临时封路或路面的实际可跑性。

在项目根目录重新生成：

```powershell
.\.venv\Scripts\python.exe android/campus-data/build_road_graph.py
```

原始 OSM 数据及派生道路图均 © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright)，按 [Open Database License (ODbL) 1.0](https://opendatacommons.org/licenses/odbl/1-0/) 提供。原始数据、派生图与生成程序随源码提供，App 的“账号”页也显示地图归属和来源链接。

## 离线校园底图

v1.7.1 的 `build_campus_map.py` 从同一份公开 OSM 数据提取建筑、绿地、湖面、运动场、道路和地名，生成 `android/app/src/main/assets/campus-map.json`。数据包含 67 个建筑面、31 个绿地区域、3 个水面、8 个运动场区域、176 条道路和 51 个去除邻近重复名称的地名标注。支持 multipolygon 的内外环；地图保留所有相关道路，不只显示用于规划路线的最大连通分量。

```powershell
.\.venv\Scripts\python.exe android/campus-data/build_campus_map.py
```

底图资产为 WGS84，`CampusMap` 在读取时统一转换成 GCJ-02，与模拟路线使用相同投影。界面缓存底图，仅在视野或尺寸改变时重绘；进度变化只更新上层轨迹和标记。底图随安装包离线提供，未生成路线时显示校园全图。没有连接外部地图 SDK，不需要地图 API 密钥。

底图资产同样按 ODbL 1.0 提供。`MapPreview.java` 使用 App 的地图模型、坐标转换和道路规划代码做桌面渲染检查；生成的 [地图预览](../../dist/campus-map-preview.png) 是数据与轨迹对齐的静态效果，不是安卓界面截图或真机验证。

## 地图页点位目录（v1.8.0）

地图底图与跑步考核点来源不同：OSM 不提供官方考核点位。当前检查了本地小程序静态包的点位页面，确认 `/xcxtapi/activity/randrunInfo` 返回本次三点，未确认全量点位接口。不得把参考仓库里的三个起点或多次随机返回的集合声称为官方完整目录。

`CampusPoints` 默认提供上述公开参考的三个已知点，明确 `complete=false`。App 将历次成功返回的校内点位加入独立目录，统一为 GCJ-02；按点位 id 或近邻坐标去重，不保存请求头、账号或会话。目录收集不改变考核点位，失败也不阻止本次自动任务。用户可在地图页导入校园点位表，格式见根目录 `campus-points.template.json`；完整性来自文件声明，App 显示该来源，未连接官方全量接口验证。

地图页初始显示校园全图，本次三点用绿色标记，目录用深色标记，点击标记查看名称。v1.9.0 支持 1–6 倍双指缩放、放大后拖动、双击放大及按钮复位。`MapViewport` 统一变换底图、路线与点位，限制拖动范围并保持缩放焦点；切换点位目录和进度刷新保留视野。底图位图按视野重绘并复用，不放大低分辨率图片；地图角标与版权不参与变换，文字、编号大小保持固定，比例尺同步调整。

`MapPreview.java` 的 `zoom` 参数生成 [全图与放大对比](../../dist/campus-map-zoom-preview.png)，使用同一 `MapViewport` 与内置字体进行桌面渲染。这是素材与投影的静态检查，没有真实手机界面、手势或锁屏验证。
