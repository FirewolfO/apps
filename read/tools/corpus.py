#!/usr/bin/env python3
"""Build and verify the paired Twenty-Four Histories corpus.

Input is deliberately structured. A filename or a book entry is never treated as
proof that a work is complete.
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
import tempfile
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CATALOG = ROOT / "app/src/main/assets/catalog.json"
DEFAULT_CONTENT = ROOT / "app/src/main/assets/content"


class CorpusError(RuntimeError):
    pass


def load_json(path: Path) -> dict[str, Any]:
    try:
        with path.open("r", encoding="utf-8") as handle:
            value = json.load(handle)
    except (OSError, json.JSONDecodeError) as error:
        raise CorpusError(f"{path}: {error}") from error
    if not isinstance(value, dict):
        raise CorpusError(f"{path}: top level must be an object")
    return value


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        json.dump(value, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def write_compact_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        json.dump(value, handle, ensure_ascii=False, separators=(",", ":"))
        handle.write("\n")


def validate_catalog(catalog: dict[str, Any]) -> list[dict[str, Any]]:
    histories = catalog.get("histories")
    if not isinstance(histories, list):
        raise CorpusError("catalog.histories must be an array")
    expected = int(catalog.get("expectedHistories", -1))
    if len(histories) != expected or expected != 24:
        raise CorpusError(f"catalog has {len(histories)} histories; expected 24")
    if int(catalog.get("expectedEditions", -1)) != 48:
        raise CorpusError("catalog.expectedEditions must be 48")
    ids = [str(item.get("id", "")) for item in histories]
    if len(set(ids)) != len(ids) or any(not item for item in ids):
        raise CorpusError("history ids must be non-empty and unique")
    total = sum(int(item.get("volumes", 0)) for item in histories)
    if total != int(catalog.get("expectedVolumes", -1)) or total != 3213:
        raise CorpusError(f"catalog volume total is {total}; expected 3213")
    return histories


def validate_bundled(catalog_path: Path, content_dir: Path, require_complete: bool) -> dict[str, Any]:
    catalog = load_json(catalog_path)
    histories = validate_catalog(catalog)
    report: dict[str, Any] = {
        "histories": len(histories),
        "editions": len(histories) * 2,
        "expectedVolumes": 3213,
        "bundledVolumes": 0,
        "completeHistories": 0,
        "items": [],
    }
    errors: list[str] = []
    for history in histories:
        history_dir = content_dir / str(history["content"])
        path = history_dir / "index.json"
        try:
            content = load_json(path)
            chapters = content.get("chapters")
            if content.get("history") != history.get("title"):
                raise CorpusError(f"{path}: history title mismatch")
            if not isinstance(chapters, list) or not chapters:
                raise CorpusError(f"{path}: chapters must be a non-empty array")
            seen: set[int] = set()
            for chapter in chapters:
                index = int(chapter.get("index", -1))
                if index in seen:
                    raise CorpusError(f"{path}: duplicate chapter index {index}")
                seen.add(index)
                for field in ("title", "file"):
                    if not str(chapter.get(field, "")).strip():
                        raise CorpusError(f"{path}: chapter {index} has empty {field}")
                volume_path = history_dir / str(chapter["file"])
                volume = load_json(volume_path)
                if int(volume.get("index", -1)) != index:
                    raise CorpusError(f"{volume_path}: index mismatch")
                for field in ("title", "original", "vernacular"):
                    if not str(volume.get(field, "")).strip():
                        raise CorpusError(f"{volume_path}: empty {field}")
                if str(volume["original"]).strip() == str(volume["vernacular"]).strip():
                    raise CorpusError(f"{volume_path}: original equals translation")
            expected_volumes = int(history["volumes"])
            availability = history.get("availability", {})
            marked_complete = bool(availability.get("originalComplete")) and bool(
                availability.get("vernacularComplete")
            )
            actually_complete = len(chapters) == expected_volumes
            if marked_complete and not actually_complete:
                raise CorpusError(
                    f"{history['title']}: marked complete with {len(chapters)}/{expected_volumes} volumes"
                )
            if require_complete and (not marked_complete or not actually_complete):
                errors.append(
                    f"{history['title']}: {len(chapters)}/{expected_volumes}, complete flag={marked_complete}"
                )
            report["bundledVolumes"] += len(chapters)
            report["completeHistories"] += int(marked_complete and actually_complete)
            report["items"].append(
                {
                    "id": history["id"],
                    "title": history["title"],
                    "bundled": len(chapters),
                    "expected": expected_volumes,
                    "complete": marked_complete and actually_complete,
                }
            )
        except CorpusError as error:
            errors.append(str(error))
    if errors:
        raise CorpusError("\n".join(errors))
    return report


def validate_source_document(path: Path, expected: int, kind: str) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    document = load_json(path)
    provenance = document.get("provenance")
    if not isinstance(provenance, dict):
        raise CorpusError(f"{path}: provenance is required")
    for field in ("sourceUrl", "license", "credit"):
        if not str(provenance.get(field, "")).strip():
            raise CorpusError(f"{path}: provenance.{field} is required")
    if kind == "vernacular" and not str(provenance.get("translator", "")).strip():
        raise CorpusError(f"{path}: provenance.translator is required")
    volumes = document.get("volumes")
    if not isinstance(volumes, list) or len(volumes) != expected:
        count = len(volumes) if isinstance(volumes, list) else 0
        raise CorpusError(f"{path}: {count}/{expected} volumes")
    normalized: list[dict[str, Any]] = []
    for position, volume in enumerate(volumes, start=1):
        index = int(volume.get("index", -1))
        title = str(volume.get("title", "")).strip()
        text = str(volume.get("text", "")).strip()
        if index != position:
            raise CorpusError(f"{path}: expected volume index {position}, got {index}")
        if not title or len(text) < 10:
            raise CorpusError(f"{path}: volume {index} has an empty title or implausibly short text")
        normalized.append({"index": index, "title": title, "text": text})
    return provenance, normalized


def build_corpus(source: Path, catalog_path: Path, content_dir: Path) -> dict[str, Any]:
    catalog = load_json(catalog_path)
    histories = validate_catalog(catalog)
    built: list[dict[str, Any]] = []
    with tempfile.TemporaryDirectory(prefix="history-corpus-") as temporary:
        staging = Path(temporary)
        for history in histories:
            history_dir = source / str(history["id"])
            expected = int(history["volumes"])
            original_provenance, originals = validate_source_document(
                history_dir / "original.json", expected, "original"
            )
            translation_provenance, translations = validate_source_document(
                history_dir / "vernacular.json", expected, "vernacular"
            )
            history["content"] = str(history["id"])
            history_staging = staging / str(history["id"])
            chapters = []
            for original, translation in zip(originals, translations):
                if original["index"] != translation["index"]:
                    raise CorpusError(f"{history['title']}: original/translation index mismatch")
                filename = f"{original['index']:04d}.json"
                chapters.append(
                    {
                        "index": original["index"] - 1,
                        "title": original["title"],
                        "file": filename,
                    }
                )
                write_compact_json(
                    history_staging / filename,
                    {
                        "index": original["index"] - 1,
                        "title": original["title"],
                        "original": original["text"],
                        "vernacular": translation["text"],
                    },
                )
            payload = {
                "history": history["title"],
                "complete": True,
                "provenance": {
                    "original": original_provenance,
                    "vernacular": translation_provenance,
                },
                "chapters": chapters,
            }
            write_json(history_staging / "index.json", payload)
            history["availability"]["originalComplete"] = True
            history["availability"]["vernacularComplete"] = True
            built.append({"id": history["id"], "volumes": len(chapters)})

        if content_dir.exists():
            shutil.rmtree(content_dir)
        shutil.copytree(staging, content_dir)
        write_json(catalog_path, catalog)

    validate_bundled(catalog_path, content_dir, require_complete=True)
    return {
        "histories": len(built),
        "editions": len(built) * 2,
        "volumes": sum(item["volumes"] for item in built),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subcommands = parser.add_subparsers(dest="command", required=True)
    validate = subcommands.add_parser("validate", help="validate bundled assets")
    validate.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    validate.add_argument("--content", type=Path, default=DEFAULT_CONTENT)
    validate.add_argument("--require-complete", action="store_true")
    build = subcommands.add_parser("build", help="build a complete paired corpus")
    build.add_argument("--source", type=Path, required=True)
    build.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    build.add_argument("--content", type=Path, default=DEFAULT_CONTENT)
    arguments = parser.parse_args()
    try:
        if arguments.command == "validate":
            result = validate_bundled(
                arguments.catalog, arguments.content, arguments.require_complete
            )
        else:
            result = build_corpus(arguments.source, arguments.catalog, arguments.content)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except CorpusError as error:
        print(f"corpus validation failed:\n{error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
