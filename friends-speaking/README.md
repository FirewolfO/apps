# 影视音频

面向 Android 15 和 Android 16 的影视原声音频播放器。应用首页是可扩展的影视目录，《老友记 Friends》是当前首个影视条目，进入后可按季、按集播放。

应用 ID 仍为 `com.firewolf.friendsspeaking`，App Center 更新通道仍使用 `friends-speaking`，因此已安装“老友记口语伴侣”的设备可以直接覆盖升级并保留播放进度与已导入文件。

## 《老友记》内网媒体

应用直接读取 `http://10.3.42.150:8000/` 中的媒体，不把剧集内容打进 APK：

- 234 集 WMA 音频由 LibVLC 解码；服务器缺少 `S10E12`、`S10E18`。由于来源不支持 HTTP Range，当前集先完整缓存到设备再播放，保证按句重听、拖动进度和恢复位置能准确寻址。缓存按最近使用顺序淘汰，最多 256 MiB。
- `S07E22` 的来源文件只有前 7 分 26 秒，`S08E18` 只有前 16 分 50 秒，并非完整一集。对齐只覆盖实际存在的音频，播放页显示“后续音频缺失”，听完片段不标记整集已完成；需补齐源文件后重新生成对应索引。已核实的片段边界以音频及末句摘要记录在 `tools/source-exceptions.json`，不适用于摘要不同的新音频。
- 226 份中英双语 PDF 台词稿在设备端提取；除了 `S10E12`、`S10E18`，`S02E24`、`S03E25`、`S04E24`、`S05E24`、`S06E25`、`S07E24`、`S08E24`、`S09E24` 也缺少 PDF。
- PDF 没有时间码。默认字幕改用开发机上根据对应 WMA 实际声音计算的逐句对齐索引；**不再按字数、句数、总时长分摊时间**。设备会核对音频 SHA-256、时长及每句台词摘要，只有匹配才启用同步；导入的同源文件也支持这一检查。
- 解析只读取正文，排除目录、简介、词汇表，合并跨页句子，并识别完整重复或末尾截断的重复台词稿。旧的 PDF 提取缓存及估算时间轴偏移不会污染新时间码。
- 片头、转场、笑声和其他台词空档不高亮上一句；PDF 中的剧集标题不当作人声台词，声音匹配置信度不足的句子不生成可跳转时间，页面显示未确认句数。没有匹配索引的 PDF 仅供手动浏览，不伪装为同步字幕。
- 用户导入 SRT 或 WebVTT 后优先使用文件本身的时间码。右上角保留 0.5 秒微调，供音频输出设备延迟等情况使用。上下拖动只浏览，不暂停或跳转音频；点击有时间码的字幕以及上一句/重听/下一句会显式跳转。

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

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。当前版本为 `1.2.1`（versionCode 5）。

App Center 的手机安装包只保留 ARM64 原生库，可减少约 150 MB 下载体积：

```bash
JAVA_HOME=/path/to/jdk-17 ./gradlew -PtargetAbi=arm64-v8a clean assembleDebug
```

## 音频对齐索引与验证

`tools/align_transcripts.py` 使用 [TorchAudio 的 wav2vec2 ASR 模型](https://docs.pytorch.org/audio/2.8/pipelines.html#wav2vec2-asr-base-960h) 及 [CTC Segmentation](https://github.com/lumaku/ctc-segmentation) 在开发机离线对齐英语台词；中文保留同一句的原始译文。20 ms 模型帧使用绝对音频采样位置合并，不随播放倍速、网络缓冲或 UI 定时器重新计时。

依赖固定在 `tools/alignment-requirements.txt`。首次运行会下载模型；使用 `pdfbox-app-2.0.27.jar` 与 Android 端相同的提取设置可加快 PDF 处理：

```bash
python tools/align_transcripts.py --cache /path/to/private-alignment-cache \
  --output app/src/main/assets/alignments --workers 20 --threads 2 \
  --pdfbox /path/to/pdfbox-app-2.0.27.jar --java /path/to/jdk-17/bin/java
```

可指定 `S01E01 S02E01` 仅处理部分剧集，或用 `--resume` 保留已完成索引。每集缓存保存音频、PDF、模型输出及 `review.json` 诊断结果；这些私人媒体与台词**不得提交进 Git**。APK 中的 `alignments/*.tsv` 仅包含文件/台词摘要、起止毫秒和匹配评分。修改解析或对齐算法后应重新生成受影响索引，不应盲目使用 `--resume`。

源音频修复后，指定对应季集并加 `--refresh-media` 重新下载；音频摘要变化会使旧模型输出失效。片段索引的 `prefix=N` 明确限定实际音频包含的台词范围，后续缺失内容必须保持无时间码，不能拉伸到现有时长中。

实际媒体集成检查会将应用的台词解析结果逐句绑定到索引，并检查时间范围、顺序、匹配覆盖率；未设置缓存目录时跳过私有媒体检查，普通单元测试仍执行：

```bash
FRIENDS_ALIGNMENT_CACHE=/path/to/private-alignment-cache \
  JAVA_HOME=/path/to/jdk-17 ./gradlew testDebugUnitTest lintDebug
```

发布前还应检查整集开头、中段、结尾的诊断记录，以及手机上正常播放、暂停恢复、倍速、拖动进度、按句跳转和锁屏恢复。模型评分不是人工逐句听审，不应将低置信度输出标记为已确认。

`PlayerSynchronizationTest` 是设备端实际媒体测试，默认跳过下载；在可访问内网的测试设备安装测试 APK 后，通过 `am instrument -e real_media true` 启用，检查进度恢复、逐句索引绑定、三处跳转、暂停、倍速和退后台继续播放。没有可用设备或模拟器未完成启动时，不应将这项测试记为通过。
