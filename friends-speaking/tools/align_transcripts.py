#!/usr/bin/env python3
"""Build audio-derived cue indexes, never distribute text over an episode's duration.

Run on the development machine. Private audio, transcripts and ASR diagnostics stay
in --cache; shipped indexes contain only hashes, timestamps and alignment scores.
Dependencies are pinned in alignment-requirements.txt. No phone-side ASR is needed.
"""
import argparse
import concurrent.futures
import hashlib
import json
import logging
import os
from pathlib import Path
import re
import subprocess
import time
import unicodedata
import urllib.parse
import urllib.request

import numpy as np
from num2words import num2words
from pypdf import PdfReader

COUNTS = (24, 24, 25, 24, 24, 25, 24, 24, 24, 18)
MISSING = {"S02E24", "S03E25", "S04E24", "S05E24", "S06E25", "S07E24",
           "S08E24", "S09E24", "S10E12", "S10E18"}
MODEL = None
LABELS = None
CJK = re.compile(r"[\u3400-\u9fff\uf900-\ufaff]")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def text_digest(text):
    return digest(re.sub(r"\s+", "", text).encode("utf-8"))


def parse_page(page):
    chinese, english, result = [], [], []

    def emit():
        text = "\n".join(part for part in (" ".join(english), "".join(chinese)) if part)
        if text and len(text) <= 1000:
            result.append(text)
        chinese.clear()
        english.clear()

    for raw in page.splitlines():
        line = re.sub(r"\s+", " ", raw).strip()
        compact = re.sub(r"\s+", "", line).lower()
        if (not line or re.fullmatch(r"\d{1,3}", line) or compact == "2.正文"
                or compact.startswith(("http://", "https://")) or "zhihu.com/people/" in compact):
            continue
        if CJK.search(line):
            if english:
                emit()
            chinese.append(line)
        elif any(c.isalpha() for c in line):
            english.append(line)
    emit()
    return result


def transcript(path, pdfbox=None, java="java"):
    pages, started = [], False
    if pdfbox:
        extracted = subprocess.check_output([java, "-Dfile.encoding=UTF-8", "-cp", str(pdfbox),
                                             str(Path(__file__).with_name("ExtractPdfText.java")), str(path)],
                                            stderr=subprocess.DEVNULL).decode("utf-8")
        page_texts = extracted.split("\f")
    else:
        page_texts = (page.extract_text() for page in PdfReader(path).pages)
    for text in page_texts:
        # The contents page contains BOTH headings; it must not start the body.
        first = next((re.sub(r"\s+", "", line) for line in text.splitlines() if line.strip()), "")
        if not started:
            if first != "2.正文":
                continue
            started = True
        if first.startswith(("3.四级词汇", "4.六级词汇")):
            break
        pages.append(text)
    result = parse_page("\n".join(pages))
    if not result:
        raise ValueError(f"No dialogue section: {path}")
    return remove_repeated_transcript(result)


def remove_repeated_transcript(result):
    # Some study notes print the complete dialogue twice. Drop only a verified
    # full repeated cycle, never individual repeated words/lines in conversation.
    canonical = [re.sub(r"\s+", "", text) for text in result]
    for period in range(10, len(result) // 2 + 1):
        if len(result) % period == 0 and all(text == canonical[i % period] for i, text in enumerate(canonical)):
            return result[:period]
    canonical = [re.sub(r"\s+", "", text) for text in result]
    # A few PDFs omit up to three final lines from the second printed copy.
    # Require the entire shared prefix to match; do not deduplicate dialogue by
    # individual repeated phrases or by an approximate similarity threshold.
    for split in range(max(10, len(result) // 2 - 2), min(len(result) - 9, len(result) // 2 + 3)):
        left, right = canonical[:split], canonical[split:]
        shared = min(len(left), len(right))
        if abs(len(left) - len(right)) > 3:
            continue
        matched = next((i for i in range(shared) if left[i] != right[i]), shared)
        if matched < shared - 3:
            continue
        left_tail, right_tail = "".join(left[matched:]), "".join(right[matched:])
        if left_tail in right_tail or right_tail in left_tail:
            return remove_repeated_transcript(result[:split] if len(left_tail) >= len(right_tail) else result[split:])
        left_zh = "".join(CJK.findall("".join(result[matched:split])))
        right_zh = "".join(CJK.findall("".join(result[split + matched:])))
        if left_zh and left_zh == right_zh:
            # The repeated copy's final English line can collide with the footer
            # URL at the bottom of the PDF page; the full shared body and Chinese
            # tail prove it is the same transcript, not a second conversation.
            return remove_repeated_transcript(result[:split])
    return result


def normalize(text):
    english = " ".join(line for line in text.splitlines() if not CJK.search(line))
    english = unicodedata.normalize("NFKD", english).replace("’", "'").replace("‘", "'")
    english = re.sub(r"\d+", lambda m: num2words(int(m[0])), english)
    return re.sub(r"[^A-Z']+", " ", english.upper()).strip()


def episode_heading(text):
    return bool(re.match(r"Friends\s+S\d{2}E\d{2}\b", text, re.IGNORECASE))


def reject_printed_headings(key, args):
    """Remove non-spoken PDF title cards without changing any speech timestamps."""
    target = args.output / f"{key}.tsv"
    if not target.is_file():
        return 0
    rows = [line.split("\t") for line in target.read_text().splitlines()]
    review = args.cache / key / "review.json"
    diagnostics = json.loads(review.read_text())
    changed = 0
    for item in diagnostics:
        if episode_heading(item["text"]):
            row = rows[item["index"] + 1]
            if row[0] != text_digest(item["text"]):
                raise ValueError(f"Review does not match index: {key}")
            changed += int(row[1] != "-1")
            row[1:3] = ["-1", "-1"]
            item["accepted"] = False
    temporary = target.with_suffix(".part")
    temporary.write_text("\n".join("\t".join(row) for row in rows) + "\n")
    temporary.replace(target)
    review.write_text(json.dumps(diagnostics, ensure_ascii=False, indent=2))
    return changed


def media_url(base, key, pdf):
    season = key[:3]
    parts = (["老友记.Friends.全10季字幕", f"老友记.Friends.{season}.240806",
              f"老友记.friends.{key}.chs&eng.240806.pdf"] if pdf else
             ["老友记.Friends.全10季音频", f"老友记.Friends.{season}", f"老友记.friends.{key}.wma"])
    return base.rstrip("/") + "/" + "/".join(urllib.parse.quote(p, safe="") for p in parts)


def download(url, path, refresh=False):
    if path.is_file() and not refresh:
        return
    temporary = path.with_suffix(path.suffix + ".part")
    for attempt in range(3):
        try:
            with urllib.request.urlopen(url, timeout=60) as response, temporary.open("wb") as output:
                expected = int(response.headers.get("Content-Length", "-1"))
                actual = 0
                while block := response.read(1024 * 1024):
                    output.write(block)
                    actual += len(block)
                if actual == 0 or (expected >= 0 and actual != expected):
                    raise OSError(f"Incomplete download: {url}")
            temporary.replace(path)
            return
        except Exception:
            if attempt == 2:
                raise


def emissions(audio, cache, threads, source_sha, trusted_legacy=False):
    import torch
    import torchaudio
    global MODEL, LABELS
    if MODEL is None:
        torch.set_num_threads(threads)
        torch.set_num_interop_threads(1)
        bundle = torchaudio.pipelines.WAV2VEC2_ASR_BASE_960H
        MODEL = bundle.get_model().eval()
        LABELS = bundle.get_labels()
    if cache.is_file():
        try:
            with np.load(cache) as saved:
                matches = str(saved["source_sha"].item()) == source_sha if "source_sha" in saved else trusted_legacy
                if matches:
                    return saved["log_probs"], float(saved["duration"])
        except (ValueError, OSError, EOFError):
            logging.warning("Rebuilding interrupted emission cache: %s", cache)
    raw = subprocess.check_output(["ffmpeg", "-v", "error", "-threads", "1", "-i", str(audio),
                                   "-ar", "16000", "-ac", "1", "-f", "f32le", "-"])
    samples = np.frombuffer(raw, dtype="<f4").copy()
    # wav2vec2 has a 320-sample stride and a 400-sample receptive field.
    # Merge on the absolute frame grid; never scale chunk times to total duration.
    frame_count = (len(samples) - 400) // 320 + 1
    result = np.empty((frame_count, len(LABELS)), dtype=np.float32)
    core_frames, context_frames = 800, 100  # 16 seconds + 2 seconds on each side
    with torch.inference_mode():
        for first in range(0, frame_count, core_frames):
            last = min(frame_count, first + core_frames)
            window_first = max(0, first - context_frames)
            window_last = min(frame_count, last + context_frames)
            waveform = torch.from_numpy(samples[window_first * 320:window_last * 320 + 80]).unsqueeze(0)
            logits, _ = MODEL(waveform)
            log_probs = logits[0].log_softmax(-1).numpy()
            result[first:last] = log_probs[first - window_first:last - window_first]
    duration = len(samples) / 16000
    temporary = cache.with_name("emissions.part.npz")
    np.savez_compressed(temporary, log_probs=result, duration=duration, source_sha=source_sha)
    temporary.replace(cache)
    return result, duration


def align(key, args):
    from ctc_segmentation import CtcSegmentationParameters, ctc_segmentation, prepare_token_list
    started = time.monotonic()
    work = args.cache / key
    work.mkdir(parents=True, exist_ok=True)
    audio, pdf = work / "audio.wma", work / "transcript.pdf"
    download(media_url(args.base, key, False), audio, args.refresh_media)
    download(media_url(args.base, key, True), pdf, args.refresh_media)
    texts = transcript(pdf, args.pdfbox, args.java)
    audio_sha = digest(audio.read_bytes())
    previous = args.output / f"{key}.tsv"
    old_header = previous.read_text().splitlines()[0].split("\t") if previous.is_file() else []
    # Older caches predate embedded provenance. Only reuse them if an existing
    # generated index independently identifies the same source audio bytes.
    trusted_legacy = len(old_header) >= 5 and old_header[0] == "FA1" and old_header[2] == audio_sha
    log_probs, duration = emissions(audio, work / "emissions.npz", args.threads, audio_sha, trusted_legacy)
    scope = len(texts)
    exceptions = json.loads(Path(__file__).with_name("source-exceptions.json").read_text())
    exception = exceptions.get(key, {})
    if exception.get("audioSha256") == audio_sha:
        endings = [i for i, text in enumerate(texts) if text_digest(text) == exception["lastCueSha256"]]
        if len(endings) != 1:
            raise ValueError(f"Partial source boundary changed: {key}")
        scope = endings[0] + 1
    normalized = [normalize(text) for text in texts]
    selected = [(i, text) for i, text in enumerate(normalized) if text and i < scope]
    vocab = {char: i for i, char in enumerate(LABELS)}
    tokens = [np.array([vocab[c] for c in text.replace(" ", "|")]) for _, text in selected]
    # Use the full search window. A narrow beam can silently pin the final scene
    # to earlier music/laughter even when the first twenty minutes match well.
    config = CtcSegmentationParameters(char_list=LABELS, index_duration=0.02,
                                       blank_transition_cost_zero=True, min_window_size=len(log_probs))
    ground_truth, beginnings = prepare_token_list(config, tokens)
    timings, probabilities, states = ctc_segmentation(config, log_probs, ground_truth)
    rows, diagnostics = [], []
    for j, (index, normalized_text) in enumerate(selected):
        first_char, last_char = beginnings[j] + 1, beginnings[j + 1] - 1
        start = max(0, round((timings[first_char] + 0.0125 - 0.10) * 1000))
        end = min(round(duration * 1000), round((timings[last_char] + 0.0125 + 0.18) * 1000))
        char_frames = np.rint(timings[first_char:last_char + 1] / 0.02).astype(int)
        char_scores = log_probs[char_frames, tokens[j]]
        score = float(np.mean(char_scores))
        # A line must match the sound, not merely fit the dynamic-programming path.
        accepted = (not episode_heading(texts[index]) and score > -2.5 and end > start
                    and (end - start) < max(10000, len(tokens[j]) * 250))
        rows.append([text_digest(texts[index]), start if accepted else -1, end if accepted else -1,
                     round(score, 4)])
        greedy_ids = log_probs[max(0, start // 20):min(len(log_probs), end // 20 + 1)].argmax(-1)
        greedy = "".join(LABELS[v] for k, v in enumerate(greedy_ids)
                         if v and (k == 0 or v != greedy_ids[k - 1])).replace("|", " ")
        diagnostics.append(dict(index=index, start=start, end=end, score=score, accepted=accepted,
                                text=texts[index], recognized=greedy, normalized=normalized_text))
    # Use the exact transcript order, including repeated utterances such as "Yes".
    aligned = iter(rows)
    ordered = [next(aligned) if normalized[i] and i < scope else [text_digest(text), -1, -1, -99]
               for i, text in enumerate(texts)]
    result = dict(format=1, episode=key, audioSha256=audio_sha,
                  pdfSha256=digest(pdf.read_bytes()), durationMs=round(duration * 1000),
                  model="WAV2VEC2_ASR_BASE_960H", cues=ordered)
    args.output.mkdir(parents=True, exist_ok=True)
    header = ["FA1", key, result["audioSha256"], result["pdfSha256"], result["durationMs"]]
    if scope < len(texts):
        header.append(f"prefix={scope}")
    target = args.output / f"{key}.tsv"
    temporary = target.with_suffix(".part")
    temporary.write_text("\n".join("\t".join(map(str, row)) for row in [header, *ordered]) + "\n")
    temporary.replace(target)
    (work / "review.json").write_text(json.dumps(diagnostics, ensure_ascii=False, indent=2))
    summary = dict(episode=key, lines=len(texts), accepted=sum(row[1] >= 0 for row in ordered),
                   sourceLines=scope,
                   duration=duration, seconds=round(time.monotonic() - started, 1),
                   lowScore=sum(item["score"] <= -2.5 for item in diagnostics))
    print(json.dumps(summary), flush=True)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("episodes", nargs="*", help="S01E01 etc.; defaults to all available episodes")
    parser.add_argument("--base", default="http://10.3.42.150:8000")
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--workers", type=int, default=1)
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--pdfbox", type=Path, help="Optional pdfbox-app-2.0.27.jar (faster, same extractor as Android)")
    parser.add_argument("--java", default="java")
    parser.add_argument("--exclude", nargs="*", default=[])
    parser.add_argument("--resume", action="store_true", help="Keep completed indexes")
    parser.add_argument("--repair-low-coverage", action="store_true", help="Rebuild completed indexes below 75% coverage")
    parser.add_argument("--refresh-media", action="store_true", help="Download selected sources again; changed audio invalidates model output")
    parser.add_argument("--reject-printed-headings", action="store_true", help="Invalidate PDF title cards in existing indexes, preserving speech timings")
    args = parser.parse_args()
    keys = args.episodes or [f"S{s:02}E{e:02}" for s, count in enumerate(COUNTS, 1)
                             for e in range(1, count + 1) if f"S{s:02}E{e:02}" not in MISSING]
    keys = [key for key in keys if key not in args.exclude]
    if args.reject_printed_headings:
        print(json.dumps({"rejectedPrintedHeadings": sum(reject_printed_headings(key, args) for key in keys)}))
        return
    if args.resume:
        keys = [key for key in keys if not (args.output / f"{key}.tsv").is_file()]
    if args.repair_low_coverage:
        selected = []
        for key in keys:
            file = args.output / f"{key}.tsv"
            if not file.is_file():
                continue
            lines = file.read_text().splitlines()
            header = lines[0].split("\t")
            rows = [line.split("\t") for line in lines[1:]]
            scope = int(header[5].removeprefix("prefix=")) if len(header) == 6 else len(rows)
            if rows and sum(int(row[1]) >= 0 for row in rows) / scope < 0.75:
                selected.append(key)
        keys = selected
    for key in keys:
        if not re.fullmatch(r"S\d{2}E\d{2}", key):
            parser.error(f"Invalid episode: {key}")
    logging.basicConfig(level=logging.WARNING)
    with concurrent.futures.ProcessPoolExecutor(max_workers=args.workers) as pool:
        futures = {pool.submit(align, key, args): key for key in keys}
        results = []
        for future in concurrent.futures.as_completed(futures):
            try:
                results.append(future.result())
            except Exception:
                logging.exception("Alignment failed: %s", futures[future])
                raise
    (args.cache / "summary.json").write_text(json.dumps(sorted(results, key=lambda x: x["episode"]), indent=2))


if __name__ == "__main__":
    main()
