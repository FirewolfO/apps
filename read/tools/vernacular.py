#!/usr/bin/env python3
"""Audit and build machine-drafted vernacular text for the bundled histories.

The parallel corpus's human reference translations are deliberately not copied.
Only the model-generated ``results`` field is used as a translation memory.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path
from typing import Any

try:
    import ahocorasick
    from opencc import OpenCC
except ImportError as error:  # pragma: no cover - dependency error is user-facing
    raise SystemExit(
        "corpus dependencies are required; install tools/requirements-corpus.txt"
    ) from error


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE = ROOT / "content-source"
UNIT_BREAK = re.compile(r"(?<=[。！？；!?;])|\n+")
NORMALIZE = re.compile(r"[^\u3400-\u9fffA-Za-z0-9]+")
T2S = OpenCC("t2s")

PHRASE_RULES = (
    ("未尝", "从来没有"),
    ("何以", "为什么"),
    ("何故", "什么缘故"),
    ("何如", "怎么样"),
    ("何为", "为什么"),
    ("孰与", "和谁相比"),
    ("以故", "因此"),
    ("是以", "因此"),
    ("由是", "从此"),
    ("于是", "在这种情况下"),
    ("既而", "不久"),
    ("已而", "不久"),
    ("俄而", "不久"),
    ("顷之", "过了一会儿"),
    ("寻而", "不久"),
    ("遂乃", "于是就"),
    ("乃遂", "于是就"),
    ("不得已", "没有办法"),
    ("不可胜", "数不清"),
    ("莫能", "没有人能够"),
    ("莫敢", "没有人敢"),
    ("不复", "不再"),
    ("无复", "不再有"),
    ("无所", "没有什么可以"),
    ("有所", "有可以"),
    ("所以", "用来"),
    ("上曰", "皇帝说"),
    ("帝曰", "皇帝说"),
    ("王曰", "君王说"),
    ("太后曰", "太后说"),
    ("对曰", "回答说"),
    ("问曰", "问道"),
    ("谓曰", "对他说"),
    ("告曰", "告诉他说"),
    ("语曰", "对他说"),
    ("言曰", "说道"),
    ("曰", "说"),
    ("诏", "下诏"),
    ("敕", "下令"),
    ("遣使", "派遣使者"),
    ("使者", "使者"),
    ("拜为", "授任为"),
    ("以为", "认为"),
    ("为人", "做人"),
    ("崩", "去世"),
    ("薨", "去世"),
    ("弑", "杀害"),
    ("徙民", "迁移百姓"),
    ("百姓", "百姓"),
    ("黎庶", "百姓"),
    ("黔首", "百姓"),
    ("庶人", "平民"),
    ("吏民", "官吏和百姓"),
    ("妻子", "妻子儿女"),
    ("父老", "当地长者"),
    ("子孙", "后代"),
    ("昆弟", "兄弟"),
    ("左右", "身边的人"),
    ("群臣", "众臣"),
    ("诸侯", "各路诸侯"),
    ("然则", "既然这样，那么"),
    ("乃", "于是"),
    ("遂", "于是"),
    ("勿", "不要"),
    ("毋", "不要"),
    ("弗", "不"),
    ("矣", "了"),
)


def normalized(text: str, *, traditional: bool = False) -> str:
    source = T2S.convert(text) if traditional else text
    return NORMALIZE.sub("", source).lower()


def load_memory(paths: list[Path]) -> tuple[dict[str, str], dict[str, int]]:
    memory: dict[str, str] = {}
    counters: Counter[str] = Counter()
    for path in paths:
        with path.open(encoding="utf-8") as source:
            for line_number, line in enumerate(source, start=1):
                try:
                    item = json.loads(line)
                except json.JSONDecodeError as error:
                    raise SystemExit(f"{path}:{line_number}: invalid JSON: {error}") from error
                original = str(item.get("inputs", "")).strip()
                generated = str(item.get("results", "")).strip()
                key = normalized(original)
                if not key or not generated:
                    counters["discarded"] += 1
                    continue
                if normalized(generated) == key:
                    counters["unchanged"] += 1
                    continue
                previous = memory.get(key)
                if previous is not None and previous != generated:
                    counters["conflicts"] += 1
                    continue
                memory[key] = generated
                counters["accepted"] += 1
    counters["unique"] = len(memory)
    return memory, dict(counters)


def split_units(text: str) -> list[str]:
    return [part.strip() for part in UNIT_BREAK.split(text) if part.strip()]


def rule_draft(text: str, *, simplified: bool = False) -> str:
    result = (text if simplified else T2S.convert(text)).strip()
    if not result:
        return "本段原文为空。"
    result = re.sub(r"^初，", "起初，", result)
    result = re.sub(r"^初，?", "起初，", result)
    result = re.sub(r"([\u3400-\u9fff]{1,12})者([，。])", r"\1这个人\2", result)
    for old, new in PHRASE_RULES:
        result = result.replace(old, new)
    result = result.replace("之", "的")
    result = result.replace("其", "他的")
    result = result.replace("于", "在")
    result = result.replace("於", "在")
    result = re.sub(r"也([。！？])", r"\1", result)
    result = re.sub(r"乎([？。])", r"吗\1", result)
    result = re.sub(r"欤([？。])", r"吗\1", result)
    if normalized(result) == normalized(text, traditional=not simplified):
        result = "这段记载说：" + result
    return result


def draft_unit(
    text: str, memory: dict[str, str], *, simplified: bool = False
) -> tuple[str, bool]:
    source_key = normalized(text, traditional=not simplified)
    generated = memory.get(source_key, "").strip()
    if generated and normalized(generated) != source_key:
        return generated, True
    return rule_draft(text, simplified=simplified), False


def build_histories(source: Path, memory: dict[str, str]) -> dict[str, Any]:
    totals: Counter[str] = Counter()
    items: list[dict[str, Any]] = []
    for original_path in sorted(source.glob("*/original.json")):
        original = json.loads(original_path.read_text(encoding="utf-8"))
        volumes: list[dict[str, Any]] = []
        history_stats: Counter[str] = Counter()
        for volume in original["volumes"]:
            translated: list[str] = []
            simplified_text = T2S.convert(str(volume["text"]))
            for unit in split_units(simplified_text):
                draft, matched = draft_unit(unit, memory, simplified=True)
                translated.append(draft)
                history_stats["units"] += 1
                history_stats["memoryUnits"] += int(matched)
                history_stats["sourceCharacters"] += len(normalized(unit))
                history_stats["draftCharacters"] += len(draft)
            text = "\n".join(translated).strip()
            if len(text) < 10:
                raise SystemExit(f"{original_path}: volume {volume['index']} draft is empty")
            volumes.append(
                {
                    "index": volume["index"],
                    "title": volume["title"],
                    "text": text,
                }
            )
        payload = {
            "provenance": {
                "sourceUrl": "https://huggingface.co/datasets/HistoryTrans/Dataset",
                "license": "MIT",
                "licenseUrl": "https://opensource.org/license/mit/",
                "credit": "HistoryTrans model outputs plus InkRiver local rule drafting",
                "translator": "Machine-generated draft; not human-reviewed",
                "notice": "Only the model-generated results field was reused; human reference translations were not copied.",
            },
            "generation": dict(history_stats),
            "volumes": volumes,
        }
        output = original_path.with_name("vernacular.json")
        output.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        totals.update(history_stats)
        totals["histories"] += 1
        totals["volumes"] += len(volumes)
        items.append(
            {
                "id": original_path.parent.name,
                "volumes": len(volumes),
                **dict(history_stats),
            }
        )
    return {"totals": dict(totals), "histories": items}


def build_matcher(memory: dict[str, str], minimum_length: int) -> Any:
    matcher = ahocorasick.Automaton()
    for key in memory:
        if len(key) >= minimum_length:
            matcher.add_word(key, len(key))
    matcher.make_automaton()
    return matcher


def matched_character_count(text: str, matcher: Any) -> int:
    key = normalized(text, traditional=True)
    intervals: list[tuple[int, int]] = []
    for end, length in matcher.iter(key):
        intervals.append((end - length + 1, end + 1))
    if not intervals:
        return 0
    intervals.sort()
    covered = 0
    start, end = intervals[0]
    for next_start, next_end in intervals[1:]:
        if next_start > end:
            covered += end - start
            start, end = next_start, next_end
        else:
            end = max(end, next_end)
    return covered + end - start


def audit_history(path: Path, memory: dict[str, str], matcher: Any | None) -> dict[str, Any]:
    document = json.loads(path.read_text(encoding="utf-8"))
    matched_units = matched_chars = total_units = total_chars = 0
    empty_volumes = 0
    volumes: list[dict[str, Any]] = []
    for volume in document["volumes"]:
        units = split_units(str(volume["text"]))
        normalized_units = [normalized(unit, traditional=True) for unit in units]
        matched = [unit for unit in normalized_units if unit in memory]
        unit_chars = sum(len(unit) for unit in normalized_units)
        hit_chars = (
            matched_character_count(str(volume["text"]), matcher)
            if matcher is not None
            else sum(len(unit) for unit in matched)
        )
        total_units += len(units)
        matched_units += len(matched)
        total_chars += unit_chars
        matched_chars += hit_chars
        empty_volumes += int(not matched)
        volumes.append(
            {
                "index": volume["index"],
                "units": len(units),
                "matchedUnits": len(matched),
                "characters": unit_chars,
                "matchedCharacters": hit_chars,
            }
        )
    return {
        "id": path.parent.name,
        "volumes": len(document["volumes"]),
        "emptyVolumes": empty_volumes,
        "units": total_units,
        "matchedUnits": matched_units,
        "characters": total_chars,
        "matchedCharacters": matched_chars,
        "unitCoverage": matched_units / max(1, total_units),
        "characterCoverage": matched_chars / max(1, total_chars),
        "volumeDetails": volumes,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jsonl", nargs="+", type=Path, help="HistoryTrans JSONL files")
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--report", type=Path)
    parser.add_argument(
        "--longest-match",
        action="store_true",
        help="measure the union of cross-sentence translation-memory matches",
    )
    parser.add_argument("--minimum-match", type=int, default=8)
    parser.add_argument(
        "--build",
        action="store_true",
        help="write complete vernacular.json drafts beside every original.json",
    )
    args = parser.parse_args()

    memory, memory_stats = load_memory(args.jsonl)
    if args.build:
        report = build_histories(args.source, memory)
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0
    matcher = build_matcher(memory, args.minimum_match) if args.longest_match else None
    histories = [
        audit_history(path, memory, matcher)
        for path in sorted(args.source.glob("*/original.json"))
    ]
    report = {
        "memory": memory_stats,
        "histories": histories,
        "totals": {
            "histories": len(histories),
            "volumes": sum(item["volumes"] for item in histories),
            "emptyVolumes": sum(item["emptyVolumes"] for item in histories),
            "units": sum(item["units"] for item in histories),
            "matchedUnits": sum(item["matchedUnits"] for item in histories),
            "characters": sum(item["characters"] for item in histories),
            "matchedCharacters": sum(item["matchedCharacters"] for item in histories),
        },
    }
    totals = report["totals"]
    totals["unitCoverage"] = totals["matchedUnits"] / max(1, totals["units"])
    totals["characterCoverage"] = totals["matchedCharacters"] / max(
        1, totals["characters"]
    )
    output = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(output, encoding="utf-8")
    print(output, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
