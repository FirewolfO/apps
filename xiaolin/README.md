# 小林学习 Android

一个面向手机阅读的 `xiaolincoding.com` 学习客户端。应用通过内置 WebView 实时呈现原站内容，并在设备本地保存：

- 最近阅读页面与滚动位置
- 每页显式完成确认
- 阅读页支持当前专题内的上一节、下一节及“完成并下一节”
- 已完成列表和最近浏览记录
- 按“系列 → 专题 → 章节/文章”浏览完整目录
- 内置 3 个系列、16 个专题、307 篇文章索引
- 支持“跟随系统 / 浅色模式 / 深色模式”，原生页面和阅读页使用一致外观

## 完整版与精简版

- **完整版**：保留现有 307 篇原站目录和实时网页阅读，原有学习进度继续使用
  `xiaolin_learning_progress`，升级后不迁移、不重置。
- **精简版**：内置 77 个高频面试重点和 28 张响应式流程/结构图，无网络时也可阅读；
  每个重点包含“30 秒回答、核心要点、面试官常追问、易错提醒”，并提供原文入口。
- 首页双按钮和各原生页面右上角都可以切换版本；精简版使用独立的
  `xiaolin_learning_progress_compact`，两个版本的完成、浏览、最近阅读和滚动位置互不影响。
- 当前选择记录在 `xiaolin_study_settings`，只是决定下次打开时显示哪个版本，不会合并进度。

## 外观模式

首页“外观模式”入口可切换跟随系统、浅色和深色三种模式。选择保存在
`xiaolin_appearance_settings`；深色模式会同步适配原生目录、学习记录、弹窗、系统状态栏、
完整版网页阅读和精简版离线速记页，不会影响两套学习进度。

目录由官网当前侧边栏生成，包括图解系列、后端八股面试系列和 AI Agent 面试八股系列。阅读正文时需要联网，目录和学习进度保存在设备本地。

## 构建

需要 JDK 17 和 Android SDK 35。

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

重新同步官网目录：

```bash
python3 tools/build_catalog.py
```

重新校验并生成精简版目录：

```bash
python3 tools/build_compact_catalog.py
```

本站文章和图片版权归小林coding原作者所有；精简版为面试要点的原创整理与自绘图示，
不复制或再发布原站文章和图片。

## 应用升级

应用从 `https://apps.lxvb.top/api/apps/xiaolin/latest` 检查新版本。发现新版后只显示确认弹窗，
只有用户点击“同意并下载”才会启动系统下载和覆盖安装。应用 ID、签名和 SharedPreferences
路径保持不变，因此覆盖升级会保留完整版与精简版历史进度；不要先卸载旧版。

公开下载页面为 `https://apps.lxvb.top/xiaolin`。
