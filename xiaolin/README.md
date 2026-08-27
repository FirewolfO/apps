# 小林学习 Android

一个面向手机阅读的 `xiaolincoding.com` 学习客户端。应用通过内置 WebView 实时呈现原站内容，并在设备本地保存：

- 最近阅读页面与滚动位置
- 每页显式完成确认
- 阅读页支持当前专题内的上一节、下一节及“完成并下一节”
- 已完成列表和最近浏览记录
- 按“系列 → 专题 → 章节/文章”浏览完整目录
- 内置 3 个系列、16 个专题、307 篇文章索引

目录由官网当前侧边栏生成，包括图解系列、后端八股面试系列和 AI Agent 面试八股系列。阅读正文时需要联网，目录和学习进度保存在设备本地。

## 构建

需要 JDK 17 和 Android SDK 35。

```bash
./gradlew test assembleDebug
```

调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

重新同步官网目录：

```bash
python3 tools/build_catalog.py
```

本站文章和图片版权归小林coding原作者所有，本应用不复制或再发布站点内容。
