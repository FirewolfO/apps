#!/usr/bin/env python3
"""Download and structure the CC BY 4.0 Twenty-Four Histories corpus."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "app/src/main/assets/catalog.json"
DEFAULT_CACHE = ROOT / "corpus-cache/ccdh"
DEFAULT_OUTPUT = ROOT / "content-source"
FOLDER_API = (
    "https://api.osf.io/v2/nodes/tp729/files/osfstorage/"
    "5e5026a88d8765008f81108b/?page%5Bsize%5D=100"
)
PROJECT_URL = "https://osf.io/tp729/"
LICENSE_URL = "https://creativecommons.org/licenses/by/4.0/"
WIKISOURCE_API = "https://zh.wikisource.org/w/api.php"
WIKISOURCE_LICENSE_URL = "https://creativecommons.org/licenses/by-sa/4.0/"
MARKER = re.compile(r"^\*(?P<history>\d{2})-(?P<volume>\d{3})\*\s*$")
SUPPLEMENTS = {
    (3, 50): "後漢書/卷50",
    (3, 51): "後漢書/卷51",
    (17, 54): "新唐書/卷054",
    (20, 69): "宋史/卷069",
}


class CcdhError(RuntimeError):
    pass


def read_json(path: Path) -> dict[str, Any]:
    try:
        with path.open("r", encoding="utf-8") as handle:
            value = json.load(handle)
    except (OSError, json.JSONDecodeError) as error:
        raise CcdhError(f"{path}: {error}") from error
    if not isinstance(value, dict):
        raise CcdhError(f"{path}: expected a JSON object")
    return value


def fetch_json(url: str) -> dict[str, Any]:
    request = urllib.request.Request(url, headers={"User-Agent": "HistoryReader/1.0"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            value = json.load(response)
    except (OSError, urllib.error.URLError, json.JSONDecodeError) as error:
        raise CcdhError(f"cannot fetch {url}: {error}") from error
    if not isinstance(value, dict):
        raise CcdhError(f"{url}: expected a JSON object")
    return value


def corpus_files() -> list[dict[str, str | int]]:
    response = fetch_json(FOLDER_API)
    items = []
    for entry in response.get("data", []):
        attributes = entry.get("attributes", {})
        name = str(attributes.get("name", ""))
        if name == "24_all_full.txt" or not re.fullmatch(
            r"\d{2}_[a-z]+_full\.txt", name
        ):
            continue
        hashes = attributes.get("extra", {}).get("hashes", {})
        download = entry.get("links", {}).get("download")
        sha256 = hashes.get("sha256")
        if not download or not sha256:
            raise CcdhError(f"{name}: missing download URL or SHA-256")
        items.append(
            {
                "name": name,
                "download": str(download),
                "sha256": str(sha256),
                "size": int(attributes.get("size", 0)),
            }
        )
    items.sort(key=lambda item: str(item["name"]))
    if len(items) != 24:
        raise CcdhError(f"OSF returned {len(items)} history files; expected 24")
    return items


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def download_file(url: str, destination: Path, expected_hash: str) -> None:
    if destination.is_file() and sha256(destination) == expected_hash:
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(url, headers={"User-Agent": "HistoryReader/1.0"})
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            with tempfile.NamedTemporaryFile(
                dir=destination.parent, prefix=destination.name + ".", delete=False
            ) as temporary:
                shutil.copyfileobj(response, temporary, length=1024 * 1024)
                temporary_path = Path(temporary.name)
    except (OSError, urllib.error.URLError) as error:
        raise CcdhError(f"cannot download {url}: {error}") from error
    actual_hash = sha256(temporary_path)
    if actual_hash != expected_hash:
        temporary_path.unlink(missing_ok=True)
        raise CcdhError(
            f"{destination.name}: SHA-256 {actual_hash}; expected {expected_hash}"
        )
    temporary_path.replace(destination)


def parse_volumes(path: Path, history_number: int) -> list[dict[str, Any]]:
    try:
        text = path.read_text(encoding="utf-8-sig")
    except (OSError, UnicodeDecodeError) as error:
        raise CcdhError(f"{path}: {error}") from error

    volumes: list[dict[str, Any]] = []
    current_index: int | None = None
    current_lines: list[str] = []

    def finish_volume() -> None:
        if current_index is None:
            return
        body = "\n".join(current_lines).strip()
        if current_index == 0:
            return
        if len(body) < 10:
            raise CcdhError(f"{path.name}: volume {current_index} is empty")
        volumes.append(
            {
                "index": current_index,
                "title": f"卷{current_index}",
                "text": body,
            }
        )

    for raw_line in text.splitlines():
        line = raw_line.strip("\ufeff\r")
        marker = MARKER.fullmatch(line.strip())
        if marker:
            marker_history = int(marker.group("history"))
            if marker_history != history_number:
                raise CcdhError(
                    f"{path.name}: found history marker {marker_history:02d}, "
                    f"expected {history_number:02d}"
                )
            finish_volume()
            current_index = int(marker.group("volume"))
            current_lines = []
        elif current_index is not None:
            current_lines.append(line)
    finish_volume()

    indexes = [volume["index"] for volume in volumes]
    if indexes != sorted(set(indexes)):
        raise CcdhError(
            f"{path.name}: duplicate or out-of-order volume markers; "
            f"got {indexes[:5]}...{indexes[-5:]}"
        )
    return volumes


def clean_wikisource_extract(text: str) -> str:
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = re.split(r"(?m)^校勘記\s*$", text, maxsplit=1)[0]
    text = re.sub(r"(?m)^〈注.*?〉\s*$", "", text)
    text = re.sub(r"\[([一二三四五六七八九十百]+)\]", "", text)
    text = re.sub(r"\*\((.*?)\)\*\*\[(.*?)\]\*", r"\2", text)
    text = re.sub(r"\*\[(.*?)\]\*", r"\1", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def fetch_wikisource_volume(
    cache: Path, history_number: int, volume_number: int
) -> tuple[dict[str, Any], dict[str, Any]]:
    page = SUPPLEMENTS.get((history_number, volume_number))
    if page is None:
        raise CcdhError(
            f"history {history_number:02d} volume {volume_number}: no supplement configured"
        )
    query = urllib.parse.urlencode(
        {
            "action": "query",
            "format": "json",
            "formatversion": "2",
            "titles": page,
            "prop": "extracts|revisions",
            "explaintext": "1",
            "exsectionformat": "plain",
            "rvprop": "ids|timestamp",
        }
    )
    result = fetch_json(f"{WIKISOURCE_API}?{query}")
    pages = result.get("query", {}).get("pages", [])
    if len(pages) != 1 or pages[0].get("missing"):
        raise CcdhError(f"Wikisource page not found: {page}")
    source = pages[0]
    text = clean_wikisource_extract(str(source.get("extract", "")))
    if len(text) < 100:
        raise CcdhError(f"Wikisource page is implausibly short: {page}")
    revision = (source.get("revisions") or [{}])[0]
    cache_path = cache / f"{history_number:02d}-{volume_number:03d}.txt"
    cache_path.parent.mkdir(parents=True, exist_ok=True)
    cache_path.write_text(text + "\n", encoding="utf-8", newline="\n")
    first_heading = next((line.strip() for line in text.splitlines() if line.strip()), "")
    volume = {
        "index": volume_number,
        "title": f"卷{volume_number}" + (f" {first_heading}" if first_heading else ""),
        "text": text,
    }
    provenance = {
        "page": page,
        "url": "https://zh.wikisource.org/wiki/" + urllib.parse.quote(page),
        "revision": revision.get("revid"),
        "timestamp": revision.get("timestamp"),
        "license": "CC BY-SA 4.0",
        "licenseUrl": WIKISOURCE_LICENSE_URL,
    }
    return volume, provenance


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        json.dump(value, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def build(cache: Path, output: Path, download: bool) -> dict[str, Any]:
    catalog = read_json(CATALOG)
    histories = catalog.get("histories", [])
    files = corpus_files()
    if len(histories) != 24:
        raise CcdhError(f"catalog has {len(histories)} histories; expected 24")

    if download:
        for source in files:
            download_file(
                str(source["download"]),
                cache / str(source["name"]),
                str(source["sha256"]),
            )

    report = []
    for position, (history, source) in enumerate(zip(histories, files), start=1):
        name = str(source["name"])
        if not name.startswith(f"{position:02d}_"):
            raise CcdhError(f"unexpected source order at {name}")
        source_path = cache / name
        if not source_path.is_file():
            raise CcdhError(f"missing {source_path}; run with --download")
        actual_hash = sha256(source_path)
        if actual_hash != source["sha256"]:
            raise CcdhError(f"{name}: local SHA-256 does not match OSF metadata")
        volumes = parse_volumes(source_path, position)
        expected_volumes = int(history["volumes"])
        by_index = {int(volume["index"]): volume for volume in volumes}
        missing = [
            index for index in range(1, expected_volumes + 1) if index not in by_index
        ]
        supplements = []
        for missing_index in missing:
            volume, provenance = fetch_wikisource_volume(
                cache / "wikisource", position, missing_index
            )
            by_index[missing_index] = volume
            supplements.append(provenance)
        volumes = [by_index[index] for index in range(1, expected_volumes + 1)]
        unexpected = sorted(index for index in by_index if index > expected_volumes)
        if len(volumes) != expected_volumes or unexpected:
            raise CcdhError(
                f"{history['title']}: CCDH has {len(volumes)} volumes; "
                f"catalog expects {expected_volumes}; unexpected={unexpected}"
            )
        payload = {
            "provenance": {
                "sourceUrl": PROJECT_URL,
                "license": "CC BY 4.0",
                "licenseUrl": LICENSE_URL,
                "credit": "Sergey Zinin and Yang Xu, Corpus of Chinese Dynastic Histories",
                "sourceFile": name,
                "sha256": actual_hash,
                "supplements": supplements,
            },
            "volumes": volumes,
        }
        write_json(output / str(history["id"]) / "original.json", payload)
        report.append(
            {
                "id": history["id"],
                "title": history["title"],
                "volumes": len(volumes),
                "characters": sum(len(volume["text"]) for volume in volumes),
            }
        )
    return {
        "histories": len(report),
        "volumes": sum(item["volumes"] for item in report),
        "characters": sum(item["characters"] for item in report),
        "items": report,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=DEFAULT_CACHE)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--download", action="store_true")
    arguments = parser.parse_args()
    try:
        result = build(arguments.cache, arguments.output, arguments.download)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except CcdhError as error:
        print(f"CCDH import failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
