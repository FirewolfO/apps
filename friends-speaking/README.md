# 老友记口语伴侣

面向 Android 15 和 Android 16 的本地英语口语学习播放器。应用内建《老友记》十季共 236 集的季/集学习位，但不附带受版权保护的剧集音频、视频、字幕或下载源。用户导入自己合法持有的媒体后，可离线使用全部学习功能。

## 内置试听

1.0.1 起，`S01E01` 默认带有一段约一分钟的原创中英双语情景对话。安装后直接进入第 1 季第 1 集，即可体验变速、字幕高亮、逐句重听和断点续播。演示对话不是《老友记》剧情、台词或录音；导入自己的 S01E01 音频或字幕后，内置演示会自动让位给用户文件。

演示音频由 Piper `en_US-ljspeech-medium` 本地生成，其模型卡标注训练数据为 public domain。可使用 `tools/generate_demo_audio.py` 和对应 Piper 模型重新生成。

## 媒体导入

首页支持一次选择媒体目录并递归扫描最多 5000 个文件。音频和字幕文件名只要包含标准季集编号即可自动匹配，例如：

```text
Friends.S01E01.mp3
Friends.S01E01.en.srt
Season 02/Friends_S02E03.m4a
Season 02/Friends_S02E03.vtt
```

支持 MP3、M4A、AAC、OGG、Opus、FLAC 和 WAV 音频，以及 SRT、WebVTT 字幕。字幕解析支持 UTF-8 和 GB18030。也可以进入任意一集后分别选择音频和字幕文件。应用通过 Android Storage Access Framework 保存只读访问权，不复制媒体，也不申请整个存储空间权限。

## 学习能力

- 十季集数依次为 24、24、25、24、24、25、24、24、24、18，总计 236 集。
- 播放速度支持 0.5x、0.75x、1.0x、1.25x、1.5x 和 2.0x。
- 当前字幕随时间轴显示为黄色，上下句使用弱化颜色；支持上一句、重听本句和下一句。
- 每集保存播放位置和时长，首页提供继续上次学习入口，季列表展示进度百分比。
- 音频、字幕 URI、进度和语速均保存在本机，覆盖升级继续保留。

## 软件更新

应用启动和返回前台时每 6 小时查询一次：

```text
https://apps.lxvb.top/api/apps/friends-speaking/latest
```

首页可手动检查。发现更高 `versionCode` 后，由用户确认，再请求系统“安装未知应用”权限，通过 DownloadManager 下载 APK，完成后打开系统安装界面。

## Android 15 / 16

项目使用 `compileSdk 36`、`targetSdk 36`、`minSdk 26`，处理强制 edge-to-edge 系统栏。应用只有 Java 字节码和 Android 资源，不包含原生库。

## 构建

需要 JDK 17 和 Android SDK Platform 36：

```bash
JAVA_HOME=/path/to/jdk-17 ./gradlew testDebugUnitTest lintDebug assembleDebug
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。应用 ID 为 `com.firewolf.friendsspeaking`，当前版为 `1.0.1`（versionCode 2）。
