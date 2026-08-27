#!/usr/bin/env python3
"""Build an auditable machine-assisted vernacular draft of the histories.

The pipeline is deliberately staged so a long CPU-only run is resumable:

1. ``fast`` uses licensed reference pairs where the complete source unit is an
   exact match, then translates every remaining chunk with the WebTrans
   classical-to-modern model.
2. ``refine`` sends only chunks that fail deterministic preservation checks to
   the larger Qwen classical-Chinese model and keeps the better candidate.
3. ``finalize`` reconstructs all 3213 volumes and writes ``vernacular.json``.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.request
from collections import Counter
from pathlib import Path
from typing import Any, Iterable


ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "app/src/main/assets/catalog.json"
DEFAULT_SOURCE = ROOT / "content-source"
DEFAULT_CACHE = ROOT / "corpus-cache/retranslation"
UNIT_BREAK = re.compile(r"(?<=[。！？；!?;])|\n+")
NORMALIZE = re.compile(r"[^\u3400-\u9fffA-Za-z0-9]+")
DATE = re.compile(
    r"(?:春|夏|秋|冬)?(?:閏|闰)?(?:正|[一二三四五六七八九十冬臘腊])月"
    r"|(?:甲|乙|丙|丁|戊|己|庚|辛|壬|癸)(?:子|丑|寅|卯|辰|巳|午|未|申|酉|戌|亥)"
)
NUMBER_WITH_UNIT = re.compile(
    r"[一二三四五六七八九十百千萬万億亿兩两〇零]+(?:餘|余|有)?"
    r"(?:人|戶|户|軍|军|兵|騎|骑|匹|石|斛|里|丈|尺|寸|年|歲|岁|日|月)"
)
COUNT = re.compile(
    r"[二三四五六七八九十百千萬万億亿兩两〇零]+"
    r"(?=戰|战|次|度|番|處|处|所|國|国|州|郡|縣|县|城|軍|军|營|营|將|将)"
)
TITLE_SUFFIXES = ("皇帝", "太后", "将军", "將軍", "帝", "王", "侯", "公")
NAMED_RULER = re.compile(
    r"(?:太祖|高祖|世祖|烈祖|圣祖|聖祖|文|武|景|昭|宣|明|成|康|惠|献|獻|哀|平|元|灵|靈|少|废|廢|末|恭|顺|順)(?:皇帝|帝|王)"
)
ONLY_MARKS = re.compile(r"^[\s\W_]+$", re.UNICODE)
REPEATED = re.compile(r"(.{8,40})\1{2,}")
NEGATION = re.compile(r"不|未|没|無|无|莫|勿|毋|弗|非|否")
DOUBLE_NEGATION = re.compile(r"(?:莫|無|无|未嘗|未尝|非|不得|沒有|没有)不")
POLARITY_TERMS = {
    "赦": ("赦", "免罪", "免除", "释放", "宽免"),
    "殺": ("杀", "斩", "诛", "处死"),
    "杀": ("杀", "斩", "诛", "处死"),
    "斬": ("杀", "斩", "诛", "处死"),
    "斩": ("杀", "斩", "诛", "处死"),
    "誅": ("杀", "斩", "诛", "处死"),
    "诛": ("杀", "斩", "诛", "处死"),
    "增": ("增", "加", "更多"),
    "減": ("减", "少", "降低"),
    "减": ("减", "少", "降低"),
    "敗": ("败", "输", "失利"),
    "败": ("败", "输", "失利"),
    "勝": ("胜", "赢", "获胜"),
    "胜": ("胜", "赢", "获胜"),
}
ENTITY_FILES = (
    "古代人名（25w）.txt",
    "中国历史地名词典.txt",
)


class TranslationError(RuntimeError):
    pass


def read_json(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as source:
        value = json.load(source)
    if not isinstance(value, dict):
        raise TranslationError(f"{path}: expected an object")
    return value


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8", newline="\n") as output:
        json.dump(value, output, ensure_ascii=False, indent=2)
        output.write("\n")
    temporary.replace(path)


def load_opencc() -> Any:
    try:
        from opencc import OpenCC
    except ImportError as error:
        raise TranslationError("OpenCC is required; add tools/requirements-corpus.txt to PYTHONPATH") from error
    return OpenCC("t2s")


def normalized(text: str, t2s: Any) -> str:
    return NORMALIZE.sub("", t2s.convert(text)).lower()


def selected_histories(worker_index: int, worker_count: int) -> list[dict[str, Any]]:
    histories = read_json(CATALOG).get("histories", [])
    if len(histories) != 24:
        raise TranslationError(f"catalog contains {len(histories)} histories, expected 24")
    return [item for index, item in enumerate(histories) if index % worker_count == worker_index]


def split_units(text: str) -> list[str]:
    units: list[str] = []
    for part in UNIT_BREAK.split(text):
        part = part.strip()
        if not part:
            continue
        if units and (ONLY_MARKS.fullmatch(part) or part in {"」", "』", "）", ")"}):
            units[-1] += part
        else:
            units.append(part)
    return units


def load_reference_memory(paths: list[Path], t2s: Any) -> tuple[dict[str, str], dict[str, int]]:
    memory: dict[str, str] = {}
    conflicted: set[str] = set()
    stats: Counter[str] = Counter()
    for path in paths:
        with path.open(encoding="utf-8") as source:
            for line_number, line in enumerate(source, 1):
                try:
                    item = json.loads(line)
                except json.JSONDecodeError as error:
                    raise TranslationError(f"{path}:{line_number}: {error}") from error
                key = normalized(str(item.get("inputs", "")), t2s)
                value = str(item.get("truth", "")).strip()
                if len(key) < 2 or not value:
                    stats["discarded"] += 1
                    continue
                previous = memory.get(key)
                if previous is not None and previous != value:
                    memory.pop(key, None)
                    conflicted.add(key)
                    stats["conflicts"] += 1
                elif key not in conflicted:
                    memory[key] = value
                    stats["accepted"] += 1
    stats["unique"] = len(memory)
    return memory, dict(stats)


class EntityIndex:
    def __init__(self, dictionary_dir: Path, t2s: Any) -> None:
        try:
            import ahocorasick
        except ImportError as error:
            raise TranslationError("pyahocorasick is required for preservation checks") from error
        matcher = ahocorasick.Automaton()
        for filename in ENTITY_FILES:
            path = dictionary_dir / filename
            with path.open(encoding="utf-8-sig") as source:
                for line in source:
                    word = t2s.convert(line.strip())
                    if 3 <= len(word) <= 12 and word not in matcher:
                        matcher.add_word(word, word)
        matcher.make_automaton()
        self.matcher = matcher
        self.rare_name_characters: set[str] = set()
        lexical = dictionary_dir / "dict.txt"
        if lexical.is_file():
            with lexical.open(encoding="utf-8-sig") as source:
                for line in source:
                    fields = line.split()
                    if len(fields) >= 3 and len(fields[0]) == 1:
                        try:
                            frequency = int(fields[1])
                        except ValueError:
                            continue
                        if frequency < 100_000 and fields[2] in {"nr", "nz"}:
                            self.rare_name_characters.add(fields[0])

    def terms(self, text: str) -> list[str]:
        matches: list[tuple[int, int, str]] = []
        for end, word in self.matcher.iter(text):
            matches.append((end - len(word) + 1, end + 1, word))
        matches.sort(key=lambda item: (item[0], -(item[1] - item[0])))
        selected: list[str] = []
        boundary = -1
        for start, end, word in matches:
            if start >= boundary:
                selected.append(word)
                boundary = end
        return selected


def quality_issues(source: str, candidate: str, entities: EntityIndex) -> list[str]:
    issues: list[str] = []
    clean = candidate.strip()
    if not clean:
        return ["empty"]
    ratio = len(clean) / max(1, len(source))
    if ratio < 0.72:
        issues.append("too-short")
    if ratio > 3.2:
        issues.append("too-long")
    if REPEATED.search(clean):
        issues.append("repetition")
    source_entities = set(entities.terms(source))
    for term in source_entities:
        if term not in clean:
            issues.append("missing-entity:" + term)
    for term in set(entities.terms(clean)) - source_entities:
        if term not in source:
            issues.append("introduced-entity:" + term)
    source_negations = len(NEGATION.findall(DOUBLE_NEGATION.sub("", source)))
    candidate_negations = len(NEGATION.findall(DOUBLE_NEGATION.sub("", clean)))
    if candidate_negations < source_negations:
        issues.append(f"lost-negation:{source_negations - candidate_negations}")
    for source_term, equivalents in POLARITY_TERMS.items():
        if source_term in source and not any(term in clean for term in equivalents):
            issues.append("lost-polarity:" + source_term)
    for suffix in TITLE_SUFFIXES:
        offset = 0
        while True:
            position = clean.find(suffix, offset)
            if position < 0:
                break
            for length in range(min(5, position), 1, -1):
                stem = clean[position - length : position]
                title = stem + suffix
                if stem in source and title not in source:
                    issues.append("introduced-title:" + title)
                    break
            offset = position + len(suffix)
    for title in NAMED_RULER.findall(clean):
        if title not in source:
            issues.append("introduced-ruler:" + title)
    for character in set(clean) - set(source):
        if character in entities.rare_name_characters:
            issues.append("introduced-name-char:" + character)
    rare_source = {
        character
        for character in source
        if 0x3400 <= ord(character) <= 0x4DBF
        or 0x20000 <= ord(character) <= 0x2FA1F
    }
    for character in rare_source:
        if character not in clean:
            issues.append("lost-rare-character:" + character)
    for value in DATE.findall(source):
        simplified = value.replace("閏", "闰").replace("臘", "腊")
        if simplified not in clean:
            issues.append("missing-date:" + simplified)
    source_numbers = [value.replace("萬", "万").replace("億", "亿").replace("餘", "余") for value in NUMBER_WITH_UNIT.findall(source)]
    missing_numbers = sum(value not in clean for value in source_numbers)
    if missing_numbers:
        issues.append(f"changed-number:{missing_numbers}")
    for value in COUNT.findall(source):
        equivalents = {value, value.replace("二", "两"), value.replace("兩", "两")}
        if not any(equivalent in clean for equivalent in equivalents):
            issues.append("changed-count:" + value)
    return issues


def issue_score(issues: Iterable[str]) -> int:
    score = 0
    for issue in issues:
        if issue.startswith(("missing-entity", "missing-date", "introduced-entity", "introduced-title", "introduced-name", "introduced-ruler", "lost-negation", "lost-polarity")):
            score += 6
        elif issue.startswith("lost-rare-character"):
            score += 5
        elif issue.startswith("changed-number"):
            score += 4
        elif issue in {"empty", "repetition"}:
            score += 10
        else:
            score += 2
    return score


class FastTranslator:
    def __init__(self, checkpoint: Path, data_dir: Path, threads: int) -> None:
        try:
            import torch
            from fairseq import checkpoint_utils
            from fairseq.dataclass.configs import GenerationConfig
        except ImportError as error:
            raise TranslationError("Fairseq runtime is required for the fast stage") from error
        torch.set_num_threads(threads)
        models, _, task = checkpoint_utils.load_model_ensemble_and_task(
            [str(checkpoint)], arg_overrides={"data": str(data_dir)}
        )
        for model in models:
            model.eval()
            for layer in model.modules():
                if hasattr(layer, "can_use_fastpath"):
                    layer.can_use_fastpath = False
        self.torch = torch
        self.models = models
        self.task = task
        self.config_class = GenerationConfig

    def translate(self, values: list[str], batch_size: int) -> list[str]:
        from fairseq.data.data_utils import collate_tokens

        results = [""] * len(values)
        dictionary = self.task.source_dictionary
        generator = self.task.build_generator(
            self.models,
            self.config_class(beam=1, max_len_a=2.2, max_len_b=24),
        )
        order = sorted(range(len(values)), key=lambda index: len(values[index]), reverse=True)
        for start in range(0, len(order), batch_size):
            indexes = order[start : start + batch_size]
            encoded = [
                dictionary.encode_line(
                    " ".join(values[index]), add_if_not_exist=False, append_eos=True
                ).long()
                for index in indexes
            ]
            tokens = collate_tokens(
                encoded,
                pad_idx=dictionary.pad(),
                eos_idx=dictionary.eos(),
                left_pad=True,
            )
            lengths = self.torch.tensor([item.numel() for item in encoded])
            sample = {"net_input": {"src_tokens": tokens, "src_lengths": lengths}}
            with self.torch.inference_mode():
                hypotheses = self.task.inference_step(generator, self.models, sample)
            for index, hypotheses_for_item in zip(indexes, hypotheses):
                results[index] = self.task.target_dictionary.string(
                    hypotheses_for_item[0]["tokens"], bpe_symbol="@@ "
                ).replace(" ", "").strip()
        return results


class RefineTranslator:
    # This fine-tune is prompt-sensitive; use the exact instruction from its model card.
    SYSTEM = "麻烦帮我翻译下面的文言文，不要出现互联网中的违禁词。"

    def __init__(self, model_path: Path, threads: int) -> None:
        try:
            import torch
            from transformers import AutoModelForCausalLM, AutoTokenizer
        except ImportError as error:
            raise TranslationError("Transformers runtime is required for the refine stage") from error
        torch.set_num_threads(threads)
        self.torch = torch
        self.tokenizer = AutoTokenizer.from_pretrained(model_path)
        self.model = AutoModelForCausalLM.from_pretrained(
            model_path, torch_dtype=torch.float32, low_cpu_mem_usage=True
        ).eval()

    def translate(self, values: list[str], batch_size: int) -> list[str]:
        results = [""] * len(values)
        tokenizer = self.tokenizer
        order = sorted(range(len(values)), key=lambda index: len(values[index]), reverse=True)
        for start in range(0, len(order), batch_size):
            indexes = order[start : start + batch_size]
            group = [values[index] for index in indexes]
            prompts = [
                tokenizer.apply_chat_template(
                    [
                        {"role": "system", "content": self.SYSTEM},
                        {"role": "user", "content": value},
                    ],
                    tokenize=False,
                    add_generation_prompt=True,
                )
                for value in group
            ]
            batch = tokenizer(prompts, padding=True, return_tensors="pt")
            maximum = min(240, max(72, int(max(map(len, group)) * 2.2)))
            with self.torch.inference_mode():
                output = self.model.generate(
                    **batch,
                    max_new_tokens=maximum,
                    do_sample=False,
                    pad_token_id=tokenizer.eos_token_id,
                )
            generated = output[:, batch.input_ids.shape[1] :]
            decoded = tokenizer.batch_decode(generated, skip_special_tokens=True)
            for index, value in zip(indexes, decoded):
                results[index] = value.strip()
        return results


class RemoteRefineTranslator:
    SYSTEM = RefineTranslator.SYSTEM

    def __init__(self, server_url: str | list[str]) -> None:
        server_urls = [server_url] if isinstance(server_url, str) else server_url
        self.endpoints = [
            (
                value.rstrip("/") + "/v1/chat/completions",
                value.rstrip("/") + "/v1/completions",
                value.rstrip("/") + "/completion",
            )
            for value in server_urls
        ]
        self.endpoint_index = 0
        self.endpoint_lock = threading.Lock()

    def next_endpoints(self) -> tuple[str, str, str]:
        with self.endpoint_lock:
            endpoints = self.endpoints[self.endpoint_index % len(self.endpoints)]
            self.endpoint_index += 1
        return endpoints

    @staticmethod
    def protect_supplementary_characters(value: str) -> tuple[str, dict[str, str]]:
        replacements: dict[str, str] = {}
        protected = value
        for character in dict.fromkeys(value):
            if ord(character) <= 0xFFFF:
                continue
            marker = f"[[U{ord(character):08X}]]"
            replacements[marker] = character
            protected = protected.replace(character, marker)
        return protected, replacements

    @staticmethod
    def restore_supplementary_characters(
        value: str, replacements: dict[str, str]
    ) -> str:
        for marker, character in replacements.items():
            value = value.replace(marker, character)
        return value

    def translate_completion(
        self,
        value: str,
        maximum: int,
        completion_endpoint: str,
        legacy_completion_endpoint: str,
    ) -> str:
        prompt = (
            f"<|im_start|>system\n{self.SYSTEM}<|im_end|>\n"
            f"<|im_start|>user\n{value}<|im_end|>\n"
            "<|im_start|>assistant\n"
        )
        request_body = json.dumps(
            {
                "model": "local",
                "prompt": prompt,
                "temperature": 0,
                "max_tokens": maximum,
                "stop": ["<|im_end|>"],
            }
        ).encode("utf-8")
        request = urllib.request.Request(
            completion_endpoint, request_body, {"Content-Type": "application/json"}
        )
        try:
            with urllib.request.urlopen(request, timeout=600) as response:
                payload = json.load(response)
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")
            if error.code == 500 and "Content-only format" in detail:
                return self.translate_legacy_completion(
                    prompt, maximum, legacy_completion_endpoint
                )
            raise TranslationError(
                f"completion HTTP {error.code}: {detail[:240]}"
            ) from error
        return str(payload["choices"][0]["text"]).strip()

    @staticmethod
    def translate_legacy_completion(
        prompt: str, maximum: int, legacy_completion_endpoint: str
    ) -> str:
        request_body = json.dumps(
            {
                "prompt": prompt,
                "temperature": 0,
                "n_predict": maximum,
                "stop": ["<|im_end|>"],
            }
        ).encode("utf-8")
        request = urllib.request.Request(
            legacy_completion_endpoint,
            request_body,
            {"Content-Type": "application/json"},
        )
        try:
            with urllib.request.urlopen(request, timeout=600) as response:
                payload = json.load(response)
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")
            raise TranslationError(
                f"legacy completion HTTP {error.code}: {detail[:240]}"
            ) from error
        return str(payload["content"]).strip()

    def translate_one(self, value: str) -> str:
        endpoint, completion_endpoint, legacy_completion_endpoint = self.next_endpoints()
        protected_value, replacements = self.protect_supplementary_characters(value)
        maximum = min(240, max(72, int(len(protected_value) * 2.2)))
        request_body = json.dumps(
            {
                "model": "local",
                "messages": [
                    {"role": "system", "content": self.SYSTEM},
                    {"role": "user", "content": protected_value},
                ],
                "temperature": 0,
                "max_tokens": maximum,
            }
        ).encode("utf-8")
        request = urllib.request.Request(
            endpoint, request_body, {"Content-Type": "application/json"}
        )
        last_error: Exception | None = None
        for attempt in range(3):
            try:
                with urllib.request.urlopen(request, timeout=600) as response:
                    payload = json.load(response)
                candidate = str(payload["choices"][0]["message"]["content"]).strip()
                return self.restore_supplementary_characters(candidate, replacements)
            except urllib.error.HTTPError as error:
                detail = error.read().decode("utf-8", errors="replace")
                if error.code == 500 and "expected peg-native format" in detail:
                    try:
                        candidate = self.translate_completion(
                            protected_value,
                            maximum,
                            completion_endpoint,
                            legacy_completion_endpoint,
                        )
                        return self.restore_supplementary_characters(
                            candidate, replacements
                        )
                    except (
                        OSError,
                        KeyError,
                        IndexError,
                        json.JSONDecodeError,
                        TranslationError,
                    ) as fallback_error:
                        last_error = fallback_error
                else:
                    last_error = TranslationError(f"HTTP {error.code}: {detail[:240]}")
            except (OSError, KeyError, IndexError, json.JSONDecodeError) as error:
                last_error = error
            time.sleep(2**attempt)
        raise TranslationError(
            f"refine server failed after three attempts for {value[:80]!r}: {last_error}"
        )

    def translate(self, values: list[str], batch_size: int) -> list[str]:
        with concurrent.futures.ThreadPoolExecutor(max_workers=batch_size) as executor:
            return list(executor.map(self.translate_one, values))


def build_chunks(text: str, memory: dict[str, str], t2s: Any, target_size: int) -> list[dict[str, Any]]:
    units = split_units(t2s.convert(text))
    chunks: list[dict[str, Any]] = []
    pending: list[str] = []

    def flush() -> None:
        if pending:
            chunks.append({"source": "".join(pending), "method": "fast", "translation": "", "issues": []})
            pending.clear()

    for unit in units:
        key = normalized(unit, t2s)
        reference = memory.get(key)
        if reference:
            flush()
            chunks.append(
                {"source": unit, "method": "reference", "translation": reference, "issues": []}
            )
            continue
        if ONLY_MARKS.fullmatch(unit):
            if pending:
                pending[-1] += unit
            elif chunks:
                if chunks[-1]["method"] == "fast":
                    chunks[-1]["source"] += unit
                else:
                    chunks[-1]["translation"] += unit
            continue
        if pending and sum(map(len, pending)) + len(unit) > target_size:
            flush()
        pending.append(unit)
    flush()
    return chunks


def run_fast(args: argparse.Namespace) -> int:
    t2s = load_opencc()
    memory, memory_stats = load_reference_memory(args.jsonl, t2s)
    entities = EntityIndex(args.dictionary, t2s)
    translator = FastTranslator(args.checkpoint, args.data, args.threads)
    for history in selected_histories(args.worker_index, args.worker_count):
        history_id = str(history["id"])
        output_path = args.cache / f"{history_id}.json"
        if output_path.is_file() and not args.force:
            print(f"{history_id}: cached", flush=True)
            continue
        document = read_json(args.source / history_id / "original.json")
        draft_volumes: list[dict[str, Any]] = []
        stats: Counter[str] = Counter()
        started = time.time()
        for volume in document["volumes"]:
            chunks = build_chunks(str(volume["text"]), memory, t2s, args.chunk_size)
            fast_chunks = [chunk for chunk in chunks if chunk["method"] == "fast"]
            translations = translator.translate(
                [chunk["source"] for chunk in fast_chunks], args.batch_size
            )
            for chunk, translation in zip(fast_chunks, translations):
                chunk["translation"] = translation
                chunk["issues"] = quality_issues(chunk["source"], translation, entities)
                stats["flaggedChunks"] += int(bool(chunk["issues"]))
            for chunk in chunks:
                stats["chunks"] += 1
                stats[chunk["method"] + "Chunks"] += 1
                stats["sourceCharacters"] += len(chunk["source"])
            draft_volumes.append(
                {"index": volume["index"], "title": volume["title"], "chunks": chunks}
            )
            if int(volume["index"]) % 10 == 0:
                print(
                    f"{history_id}: {volume['index']}/{len(document['volumes'])} volumes",
                    flush=True,
                )
        payload = {
            "schemaVersion": 1,
            "history": history_id,
            "stage": "fast",
            "memory": memory_stats,
            "stats": {**stats, "seconds": round(time.time() - started, 2)},
            "volumes": draft_volumes,
        }
        write_json(output_path, payload)
        print(f"{history_id}: wrote {output_path} {dict(stats)}", flush=True)
    return 0


def run_refine(args: argparse.Namespace) -> int:
    t2s = load_opencc()
    entities = EntityIndex(args.dictionary, t2s)
    if args.server_url:
        translator: RefineTranslator | RemoteRefineTranslator = RemoteRefineTranslator(
            args.server_url
        )
    elif args.model:
        translator = RefineTranslator(args.model, args.threads)
    else:
        raise TranslationError("refine requires either --model or --server-url")
    for history in selected_histories(args.worker_index, args.worker_count):
        history_id = str(history["id"])
        path = args.cache / f"{history_id}.json"
        document = read_json(path)
        flagged: list[dict[str, Any]] = []
        warnings = 0
        previously_reviewed = 0
        for volume in document["volumes"]:
            for chunk in volume["chunks"]:
                method = chunk.get("method")
                if method == "reference":
                    continue
                if method == "fast":
                    chunk["issues"] = quality_issues(
                        chunk["source"], chunk["translation"], entities
                    )
                else:
                    previously_reviewed += int(
                        method in {"qwen-refined", "fast-retained"}
                    )
                warnings += int(bool(chunk["issues"]))
                if method == "fast" and issue_score(chunk["issues"]) >= args.min_issue_score:
                    flagged.append(chunk)
        document.setdefault("stats", {})["warningChunks"] = warnings
        document["stats"]["eligibleRefineChunks"] = previously_reviewed + len(flagged)
        if not flagged:
            document["stage"] = "refined"
            write_json(path, document)
            print(f"{history_id}: no flagged chunks", flush=True)
            continue
        started = time.time()
        for start in range(0, len(flagged), args.refine_window):
            window = flagged[start : start + args.refine_window]
            candidates = translator.translate(
                [chunk["source"] for chunk in window], args.batch_size
            )
            for chunk, candidate in zip(window, candidates):
                candidate_issues = quality_issues(chunk["source"], candidate, entities)
                old_score = issue_score(chunk["issues"])
                new_score = issue_score(candidate_issues)
                chunk["fastTranslation"] = chunk["translation"]
                chunk["refineIssues"] = candidate_issues
                if new_score < old_score:
                    chunk["translation"] = candidate
                    chunk["method"] = "qwen-refined"
                    chunk["issues"] = candidate_issues
                else:
                    chunk["method"] = "fast-retained"
            print(f"{history_id}: refined {min(start + len(window), len(flagged))}/{len(flagged)}", flush=True)
            write_json(path, document)
        document["stage"] = "refined"
        document.setdefault("stats", {})["refinedChunks"] = previously_reviewed + len(flagged)
        document["stats"]["acceptedRefinements"] = sum(
            chunk.get("method") == "qwen-refined"
            for volume in document["volumes"]
            for chunk in volume["chunks"]
        )
        document["stats"]["retainedFastChunks"] = sum(
            chunk.get("method") == "fast-retained"
            for volume in document["volumes"]
            for chunk in volume["chunks"]
        )
        document["stats"]["refineSeconds"] = round(time.time() - started, 2)
        write_json(path, document)
    return 0


def run_finalize(args: argparse.Namespace) -> int:
    totals: Counter[str] = Counter()
    for history in selected_histories(0, 1):
        history_id = str(history["id"])
        draft = read_json(args.cache / f"{history_id}.json")
        if draft.get("stage") != "refined" and not args.allow_fast:
            raise TranslationError(f"{history_id}: refine stage is incomplete")
        volumes: list[dict[str, Any]] = []
        unresolved = 0
        for volume in draft["volumes"]:
            text = "\n".join(
                str(chunk.get("translation", "")).strip()
                for chunk in volume["chunks"]
                if str(chunk.get("translation", "")).strip()
            )
            if len(text) < 10:
                raise TranslationError(f"{history_id} volume {volume['index']}: empty translation")
            unresolved += sum(bool(chunk.get("issues")) for chunk in volume["chunks"])
            volumes.append({"index": volume["index"], "title": volume["title"], "text": text})
        totals["histories"] += 1
        totals["volumes"] += len(volumes)
        totals["unresolvedChunks"] += unresolved
        payload = {
            "provenance": {
                "sourceUrl": "https://huggingface.co/bangboom/chinese-translation-models",
                "license": "Apache-2.0 and MIT",
                "licenseUrl": "https://www.apache.org/licenses/LICENSE-2.0",
                "credit": "WebTrans, HistoryTrans reference pairs, and Qwen classical-Chinese translation model",
                "translator": "Machine-assisted complete draft; not line-by-line human-reviewed",
                "notice": "Exact licensed reference pairs were combined with two machine translation passes and deterministic preservation checks.",
                "referenceDataset": "https://huggingface.co/datasets/HistoryTrans/Dataset",
                "refineModel": "https://huggingface.co/rkingzhong/qwen2.5-3b-classical-chinese-trans",
            },
            "generation": {**draft.get("stats", {}), "unresolvedChunks": unresolved},
            "volumes": volumes,
        }
        write_json(args.source / history_id / "vernacular.json", payload)
        print(f"{history_id}: {len(volumes)} volumes, {unresolved} unresolved flags", flush=True)
    print(json.dumps(dict(totals), ensure_ascii=False, indent=2))
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    subcommands = parser.add_subparsers(dest="stage", required=True)
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    common.add_argument("--cache", type=Path, default=DEFAULT_CACHE)
    common.add_argument("--dictionary", type=Path, required=True)
    common.add_argument("--worker-index", type=int, default=0)
    common.add_argument("--worker-count", type=int, default=1)
    common.add_argument("--threads", type=int, default=max(1, (os.cpu_count() or 8) // 2))
    common.add_argument("--batch-size", type=int, default=64)

    fast = subcommands.add_parser("fast", parents=[common])
    fast.add_argument("jsonl", nargs="+", type=Path)
    fast.add_argument("--checkpoint", type=Path, required=True)
    fast.add_argument("--data", type=Path, required=True)
    fast.add_argument("--chunk-size", type=int, default=80)
    fast.add_argument("--force", action="store_true")

    refine = subcommands.add_parser("refine", parents=[common])
    refine.set_defaults(batch_size=8)
    refine.add_argument("--model", type=Path)
    refine.add_argument("--server-url", action="append")
    refine.add_argument("--min-issue-score", type=int, default=12)
    refine.add_argument("--refine-window", type=int, default=256)

    finalize = subcommands.add_parser("finalize")
    finalize.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    finalize.add_argument("--cache", type=Path, default=DEFAULT_CACHE)
    finalize.add_argument("--allow-fast", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if hasattr(args, "worker_count") and not 0 <= args.worker_index < args.worker_count:
        raise TranslationError("worker-index must be within worker-count")
    if args.stage == "fast":
        return run_fast(args)
    if args.stage == "refine":
        return run_refine(args)
    return run_finalize(args)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, json.JSONDecodeError, TranslationError) as error:
        print(f"retranslation failed: {error}", file=sys.stderr)
        raise SystemExit(1)
