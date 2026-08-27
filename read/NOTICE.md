# 内容说明

应用内置的二十四史 3213 卷原文来自 Corpus of Chinese Dynastic Histories：
https://osf.io/tp729/ ，许可为 CC BY 4.0，署名 Sergey Zinin and Yang Xu。
底本缺少的《后汉书》卷五十、卷五十一、《新唐书》卷五十四和《宋史》卷六十九，
补自中文维基文库，许可为 CC BY-SA 4.0。各部文件保存了来源文件名、校验和与补卷记录。

各卷正式题名从中文维基文库的二十四史目录提取并逐部校验，目录内容依 CC BY-SA 4.0 使用。

白话内容是机器辅助初译，不是权威人工译本。重译器优先采用 HistoryTrans Dataset 中与
原文完整匹配的 `truth` 参考译文；未匹配段落由 WebTrans 文言文翻译模型生成，再对专名、
日期、数量、否定含义、篇幅和重复等多项风险叠加的段落使用 Qwen2.5 文言翻译模型复核，
并只保留检查分数严格改善的候选。HistoryTrans Dataset 标示为 MIT 许可：
https://huggingface.co/datasets/HistoryTrans/Dataset 。

WebTrans 模型标示为 Apache-2.0：
https://huggingface.co/bangboom/chinese-translation-models 。二次复核模型标示为 Apache-2.0：
https://huggingface.co/rkingzhong/qwen2.5-3b-classical-chinese-trans 。

机器辅助初译未经逐句人工通校，可能包含误译、漏译、专名错误和不自然语序。应用书架、
目录和关于页均明确标注“机器辅助初译”，建议与原文对照阅读。

`tools/corpus.py` 对 24 部、3213 卷执行目录、卷号、非空和原译配对校验后才允许构建。

书目中的作者、朝代、卷数与简介仅用于索引和导读。
