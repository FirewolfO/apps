# Players 影厅

面向 Android 手机、平板和折叠屏的在线视频应用。界面采用深色双列卡片、精选焦点图、分类筛选和底部导航，播放器支持原画/高清切换、字幕、断点续播、收藏、按钮横屏全屏和画中画。

## 资源更新

- `catalog/catalog.json` 是随 APK 内置且可通过 GitHub Raw 远程更新的精选片单。当前内置 25 部中国经典电影和动画，以及 Blender 开放电影、4K CC 影像和其他公有领域电影；仓库推送新片单后，已安装应用无需升级即可读取。
- Wikimedia Commons 中国电影分类是实时开放片源。应用每 12 小时读取该分类，只接收资源页明确标注为 Public Domain 的完整影片，自动选择可用的 WebM 原画、480P 和 240P 转码。新上传的合规影片无需升级应用即可出现。
- NASA Image and Video Library 是实时开放视频源。应用启动、下拉刷新和每 12 小时后台任务会读取最近两年的条目，并解析官方原画、高清、流畅和字幕地址。
- 设置页可替换为自有 HTTPS 片单。远程请求失败时继续使用上次缓存和内置片单，不会让首页变空。
- 片源只接受 HTTPS；片单最多 500 条、响应最多 4 MiB。中国经典影片逐条链接到 Wikimedia Commons 授权页，其他内置电影来自 Blender Open Movie 并按 Creative Commons Attribution 3.0 使用；NASA 条目展示其官方媒体使用条款入口。

应用不内置盗版站、破解 VIP、嗅探解析或绕过 DRM 的能力。加入商业电影或剧集时，应提供版权方、发行方或用户自有媒体服务器授权的直链/HLS/DASH 地址。

远程片单格式：

```json
{
  "version": 1,
  "updatedAt": "2026-09-15T00:00:00Z",
  "items": [{
    "id": "stable-id",
    "title": "片名",
    "summary": "简介",
    "posterUrl": "https://example.com/poster.jpg",
    "source": "版权方或片库名称",
    "category": "电影",
    "year": "2026",
    "badge": "4K",
    "addedAt": "2026-09-15T00:00:00Z",
    "license": {"name": "授权名称", "url": "https://example.com/terms"},
    "streams": [{
      "label": "4K",
      "url": "https://example.com/video.mpd",
      "mimeType": "application/dash+xml",
      "subtitleUrl": "https://example.com/zh.vtt"
    }]
  }]
}
```

`streams` 按画质从高到低排列。播放器可识别 MP4/MOV/MKV、HLS 和 DASH；实际解码能力取决于设备硬件。

提交片单前校验结构、授权字段及全部资源连通性：

```bash
python3 tools/validate_catalog.py --network
```

## Android 15 / 16

项目使用 `compileSdk 36`、`targetSdk 36`，完整处理 Android 15 起强制的 edge-to-edge 系统栏，并允许 Android 16 在大屏设备上自由调整窗口、方向和比例。应用只有 Java 字节码和 Android 资源，不包含需要额外验证 16 KiB 页大小的原生库。

## 构建

需要 JDK 17 和 Android SDK Platform 36：

```bash
JAVA_HOME=/path/to/jdk-17 ./gradlew testDebugUnitTest lintDebug assembleDebug
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。应用 ID 为 `com.firewolf.players`，版本为 `1.1.0`（versionCode 2）。应用每 6 小时从 `https://apps.lxvb.top/api/apps/players/latest` 检查新版，也可在片源设置中手动检查；用户确认并授予安装权限后，应用会下载 APK 并打开系统安装界面。
