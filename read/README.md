# 廿四史 Android 阅读器

原生 Android 离线阅读器，提供 Android 11（API 30）和 Android 16（API 36）安装包。
工程没有第三方运行时依赖。

## 已实现

- 24 部正史、原文/白话共 48 个版本入口
- 书架搜索、版本筛选、收藏、最近阅读
- 目录、章节跳转、原文/白话切换、页内查找
- 按书自动保存卷号与卷内位置，原文/白话分别记位，重启手机后可恢复
- 文字选择高亮、摘录、书签、批注编辑与删除
- Android 系统中文 TTS 朗读
- 本地 TXT 导入、JSON 阅读数据导出、系统自动备份
- Android 16 边到边布局和状态栏/导航栏适配

## 语料状态

当前仓库内置二十四史 3213 卷完整原文，以及逐卷非空的 3213 卷白话机器辅助初译，合计
24 部、48 个版本入口。每卷目录采用中文维基文库校核后的正式题名，保留本纪、志、表、
列传及篇主人物等信息，不再使用只有卷号的占位标题。

白话重译优先采用 HistoryTrans 中与原文完全匹配的可靠对照；其余段落由 WebTrans 文言翻译
模型生成初稿，再将专名、日期、数量、否定含义、篇幅和重复等多项风险叠加的段落交给较大
的文言翻译模型复核，并只保留检查分数严格改善的候选。译稿未经逐句人工通校，仍可能有误译或生硬语序；应用内
始终显示“机器辅助初译”，不把它冒充权威人工全译。

`preBuild` 会检查书目、目录索引、每个按卷正文文件、原译配对和完整标记；任何少卷、
空卷、卷号不连续或原译相同的条目都不能通过构建。

```bash
python3 tools/corpus.py validate
python3 tools/corpus.py validate --require-complete
```

第二条命令必须报告 `bundledVolumes: 3213` 和 `completeHistories: 24`。

## 完整语料格式

每部书在 `content-source/<书目 id>/` 下放两个结构化文件：

```text
content-source/
  shiji/
    original.json
    vernacular.json
```

每个文件必须声明来源、许可与署名。白话文件还必须声明译者：

```json
{
  "provenance": {
    "sourceUrl": "https://example.org/source",
    "license": "Public Domain",
    "credit": "底本与整理说明",
    "translator": "白话文件必填"
  },
  "volumes": [
    {"index": 1, "title": "卷一 五帝本纪第一", "text": "正文"}
  ]
}
```

准备好全部语料后运行：

```bash
python3 tools/corpus.py build --source content-source
python3 tools/corpus.py validate --require-complete
```

构建器会要求卷号从 1 连续到该书卷数，并一一配对原文和白话。校验全部通过后，
它把正文写成 `assets/content/<书目 id>/index.json` 加逐卷 JSON，阅读器只加载当前卷。

原文构建、正式卷名校核与白话重译工具分别为：

```bash
python3 tools/ccdh.py
python3 tools/titles.py --apply
python3 tools/retranslate.py fast /path/to/train.jsonl /path/to/val.jsonl \
  /path/to/test.jsonl --dictionary /path/to/dictionary \
  --checkpoint /path/to/model_old2new.pt --data /path/to/dataflow_old2new
python3 tools/retranslate.py refine --dictionary /path/to/dictionary \
  --model /path/to/qwen2.5-3b-classical-chinese-trans
python3 tools/retranslate.py finalize
```

依赖版本记录在 `tools/requirements-corpus.txt`。语料来源和许可见 `NOTICE.md`。

## 构建

需要 JDK 17 与 Android SDK Platform 36。运行全部变体测试并分别构建：

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew test \
  assembleAndroid11Debug assembleAndroid16Debug
```

APK 分别输出到：

```text
app/build/outputs/apk/android11/debug/app-android11-debug.apk
app/build/outputs/apk/android16/debug/app-android16-debug.apk
```
