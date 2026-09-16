# 影视音频

面向 Android 15 和 Android 16 的影视原声音频播放器。应用首页是可扩展的影视目录，《老友记 Friends》是当前首个影视条目，进入后可按季、按集播放。

应用 ID 仍为 `com.firewolf.friendsspeaking`，App Center 更新通道仍使用 `friends-speaking`，因此已安装“老友记口语伴侣”的设备可以直接覆盖升级并保留播放进度与已导入文件。

## 《老友记》内网媒体

应用直接读取 `http://10.3.42.150:8000/` 中的媒体，不把剧集内容打进 APK：

- 234 集 WMA 音频由 LibVLC 解码并在线播放；服务器缺少 `S10E12`、`S10E18`。
- 226 份中英双语 PDF 台词稿在设备端提取为滚动字幕；除上述两集外，各季最后一份台词稿也未出现在服务器上。
- PDF 原稿没有字幕时间码。应用根据每条台词内容长度生成整集内容进度时间轴；可从右上角字幕菜单把中间台词与当前声音对齐，或按 0.5 秒微调，校准结果按剧集保存。上下拖动只浏览字幕，不会暂停或跳转音频；点击某条字幕及上一句/重听/下一句会显式跳转。
- 用户导入带时间码的 SRT 或 WebVTT 后，会覆盖 PDF 台词稿并使用精确时间轴。

媒体服务器使用内网明文 HTTP，网络安全配置只对 `10.3.42.150` 开放明文访问，其他地址仍要求 HTTPS。

## 本地媒体覆盖

剧集页支持一次选择媒体目录并递归扫描最多 5000 个文件。音频和字幕文件名只要包含标准季集编号即可自动匹配，例如：

```text
Friends.S01E01.wma
Friends.S01E01.srt
Season 02/Friends_S02E03.m4a
Season 02/Friends_S02E03.pdf
```

支持 WMA、MP3、M4A、AAC、OGG、Opus、FLAC 和 WAV 音频，以及 PDF、SRT、WebVTT 字幕。SRT/VTT 解析支持 UTF-8 和 GB18030。也可以进入任意一集后分别选择音频和字幕文件。应用通过 Android Storage Access Framework 保存只读访问权，不复制媒体，也不申请整个存储空间权限。

## 播放能力

- 十季集数依次为 24、24、25、24、24、25、24、24、24、18，共 236 个剧集位。
- 播放速度支持 0.5x、0.75x、1.0x、1.25x、1.5x 和 2.0x。
- 字幕列表随播放滚动，将当前台词稳定显示在画面中间；拖动浏览不影响播放，点击台词可跳转。
- 播放页保持亮屏；锁屏或切换应用后由前台媒体服务继续播放，并可通过系统媒体通知控制。
- 音频、字幕选择位于右上角；底部播放工具只占一行，倍速通过弹出菜单选择。
- 每集保存播放位置和时长，影视目录提供继续播放入口，剧集列表展示进度。
- 已播放完的剧集以绿色和“已播放完”单独标识。
- 本地音频、字幕 URI、进度和语速均保存在本机，覆盖升级继续保留。

## 软件更新

应用启动和返回前台时每 6 小时查询一次：

```text
https://apps.lxvb.top/api/apps/friends-speaking/latest
```

首页可手动检查。发现更高 `versionCode` 后，由用户确认，再请求系统“安装未知应用”权限，通过 DownloadManager 下载 APK，完成后打开系统安装界面。

## Android 15 / 16

项目使用 `compileSdk 36`、`targetSdk 36`、`minSdk 26`，处理强制 edge-to-edge 系统栏。LibVLC 3.7.6 的 ARM64 原生库使用 16 KiB ELF LOAD 对齐，通用 APK 也通过 `zipalign -P 16` 检查。

PDF 文本提取使用 Apache-2.0 许可的 PdfBox-Android；WMA 播放使用 LGPL-2.1 许可的 LibVLC Android。

## 构建

需要 JDK 17 和 Android SDK Platform 36：

```bash
JAVA_HOME=/path/to/jdk-17 ./gradlew testDebugUnitTest lintDebug assembleDebug
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。当前版本为 `1.2.0`（versionCode 4）。

App Center 的手机安装包只保留 ARM64 原生库，可减少约 150 MB 下载体积：

```bash
JAVA_HOME=/path/to/jdk-17 ./gradlew -PtargetAbi=arm64-v8a clean assembleDebug
```
