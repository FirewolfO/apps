#!/usr/bin/env python3
"""Fetch canonical Twenty-Four Histories volume titles from Wikisource."""

from __future__ import annotations

import argparse
import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "app/src/main/assets/catalog.json"
DEFAULT_SOURCE = ROOT / "content-source"
DEFAULT_CACHE = ROOT / "corpus-cache/titles"
API = "https://zh.wikisource.org/w/api.php"
LICENSE_URL = "https://creativecommons.org/licenses/by-sa/4.0/"
BOOK_PAGES = (
    "史記", "漢書", "後漢書", "三國志", "晉書", "宋書", "南齊書", "梁書",
    "陳書", "魏書", "北齊書", "周書", "隋書", "南史", "北史", "舊唐書",
    "新唐書", "舊五代史", "新五代史", "宋史", "遼史", "金史", "元史", "明史",
)
VOLUME_LINK = re.compile(r"/卷0*(\d+)(?:[上中下]|之[一二三四五六七八九十]+)?$")
SECTION = re.compile(r"(?mi)^\s*\|\s*section\s*=\s*(.*?)\s*$")
HEADING = re.compile(r"(?m)^==+\s*([^=\n]+?)\s*==+\s*$")
TOC_LINK = re.compile(r"^\s*[*#]\s*\[\[([^\]|]+)[|]([^\]]+)\]\](.*)$")


class TitleError(RuntimeError):
    pass


def fetch(parameters: dict[str, str], attempts: int = 5) -> dict[str, Any]:
    query = urllib.parse.urlencode(parameters)
    request = urllib.request.Request(
        f"{API}?{query}", headers={"User-Agent": "HistoryReader/2.0 (volume titles)"}
    )
    for attempt in range(1, attempts + 1):
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                value = json.load(response)
            if not isinstance(value, dict):
                raise TitleError("MediaWiki returned a non-object response")
            return value
        except (OSError, urllib.error.URLError, json.JSONDecodeError) as error:
            if attempt == attempts:
                raise TitleError(f"MediaWiki request failed: {error}") from error
            time.sleep(attempt * 2)
    raise AssertionError("unreachable")


def read_json(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as source:
        value = json.load(source)
    if not isinstance(value, dict):
        raise TitleError(f"{path}: expected an object")
    return value


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as output:
        json.dump(value, output, ensure_ascii=False, indent=2)
        output.write("\n")


def clean_markup(value: str) -> str:
    value = re.sub(r"<!--.*?-->", "", value, flags=re.S)
    value = re.sub(r"<(?:sub|small)\b[^>]*>.*?</(?:sub|small)>", "", value, flags=re.I | re.S)
    value = re.sub(r"\[\[[^\]|]+\|([^\]]+)\]\]", r"\1", value)
    value = re.sub(r"\[\[([^\]]+)\]\]", r"\1", value)
    value = re.sub(r"\{\{lang\|[^|{}]+\|([^{}]+)\}\}", r"\1", value)
    value = re.sub(r"\{\{[^{}]*\}\}", "", value)
    value = re.sub(r"-\{([^{}]+)\}-", r"\1", value)
    value = re.sub(r"<[^>]+>", "", value)
    value = value.replace("'''", "").replace("''", "")
    value = re.sub(r"\s*[:：]\s*", "·", value)
    return re.sub(r"\s+", " ", value).strip(" -—|　·")


def root_wikitext(book_page: str) -> tuple[str, dict[str, Any]]:
    response = fetch(
        {
            "action": "query",
            "format": "json",
            "formatversion": "2",
            "titles": book_page,
            "prop": "revisions",
            "rvprop": "ids|timestamp|content",
            "rvslots": "main",
        }
    )
    pages = response.get("query", {}).get("pages", [])
    if not pages or not pages[0].get("revisions"):
        raise TitleError(f"{book_page}: root page has no wikitext")
    revision = pages[0]["revisions"][0]
    return str(revision.get("slots", {}).get("main", {}).get("content", "")), {
        "revisionId": revision.get("revid"),
        "revisionTimestamp": revision.get("timestamp"),
    }


def root_titles(book_page: str, text: str) -> dict[int, list[str]]:
    titles: dict[int, list[str]] = {}
    for line in text.splitlines():
        match = TOC_LINK.search(line)
        if not match:
            continue
        target = match.group(1).strip().lstrip("/")
        if target.startswith(book_page + "/"):
            target = target[len(book_page) + 1 :]
        volume_match = re.match(
            r"卷0*(\d+)(?:[上中下b]|之[一二三四五六七八九十]+)?$", target
        )
        if not volume_match:
            continue
        title = clean_markup(match.group(3))
        if title:
            titles.setdefault(int(volume_match.group(1)), []).append(title)
    return titles


def merge_root_titles(values: list[str]) -> str:
    if not values:
        return ""
    return re.sub(
        r"([第一二三四五六七八九十百]+)[上中下](?=$| )", r"\1", values[0]
    )


def title_from_wikitext(text: str, volume: int) -> str:
    match = SECTION.search(text)
    candidate = clean_markup(match.group(1)) if match else ""
    if not candidate:
        heading = HEADING.search(text)
        candidate = clean_markup(heading.group(1)) if heading else ""
    if not candidate:
        return f"卷{volume}"
    if re.match(r"^卷(?:[一二三四五六七八九十百千零〇兩两0-9]+)", candidate):
        return candidate
    return f"卷{volume} {candidate}"


def discover_volume_pages(book_page: str) -> dict[int, list[str]]:
    result = fetch(
        {
            "action": "parse",
            "format": "json",
            "formatversion": "2",
            "page": book_page,
            "prop": "links",
        }
    )
    pages: dict[int, list[str]] = {}
    for link in result.get("parse", {}).get("links", []):
        title = str(link.get("title", ""))
        if not title.startswith(book_page + "/"):
            continue
        match = VOLUME_LINK.search(title)
        if match:
            pages.setdefault(int(match.group(1)), []).append(title)
    for value in pages.values():
        value.sort()
    return pages


def fetch_wikitexts(page_titles: list[str]) -> dict[str, str]:
    result: dict[str, str] = {}
    for start in range(0, len(page_titles), 15):
        batch = page_titles[start : start + 15]
        response = fetch(
            {
                "action": "query",
                "format": "json",
                "formatversion": "2",
                "titles": "|".join(batch),
                "prop": "revisions",
                "rvprop": "ids|timestamp|content",
                "rvslots": "main",
            }
        )
        for page in response.get("query", {}).get("pages", []):
            if page.get("missing"):
                continue
            revisions = page.get("revisions") or []
            if not revisions:
                continue
            content = revisions[0].get("slots", {}).get("main", {}).get("content", "")
            result[str(page["title"])] = str(content)
    return result


def navigation_title(texts: dict[str, str], pages: list[str]) -> str:
    basenames = [page.rsplit("/", 1)[-1] for page in pages]
    for text in texts.values():
        for basename in basenames:
            pattern = re.compile(
                r"\[\[\.\./" + re.escape(basename) + r"\|([^\]]+)\]\]"
            )
            match = pattern.search(text)
            if match:
                return clean_markup(match.group(1))
    return ""


def merge_split_title(titles: list[str], volume: int) -> str:
    descriptive = [title for title in titles if not re.fullmatch(r"卷\d+", title)]
    if not descriptive:
        return f"卷{volume}"
    title = descriptive[0]
    title = re.sub(r"^(卷[^ ]+?)[上下](?= )", r"\1", title)
    title = re.sub(r"(第[^ ]+?)[上下](?=$| )", r"\1", title)
    return title


def fetch_book_titles(book_page: str, expected: int) -> tuple[list[str], dict[str, Any]]:
    root_text, root_revision = root_wikitext(book_page)
    toc = root_titles(book_page, root_text)
    discovered = discover_volume_pages(book_page)
    missing = [index for index in range(1, expected + 1) if index not in discovered]
    if missing:
        candidates = []
        for index in missing:
            candidates.extend(
                [f"{book_page}/卷{index:03d}", f"{book_page}/卷{index}"]
            )
        candidate_texts = fetch_wikitexts(candidates)
        for index in missing:
            for candidate in (f"{book_page}/卷{index:03d}", f"{book_page}/卷{index}"):
                if candidate in candidate_texts:
                    discovered[index] = [candidate]
                    break
    unresolved = [index for index in range(1, expected + 1) if index not in discovered]
    if unresolved:
        raise TitleError(f"{book_page}: missing volume pages {unresolved}")

    ordered_groups = [discovered[index] for index in range(1, expected + 1)]
    all_pages = [page for group in ordered_groups for page in group]
    texts = fetch_wikitexts(all_pages)
    missing_text = [page for page in all_pages if page not in texts]
    if missing_text:
        raise TitleError(f"{book_page}: missing revisions for {missing_text}")
    titles = []
    for index, pages in enumerate(ordered_groups, 1):
        page_titles = [title_from_wikitext(texts[page], index) for page in pages]
        toc_title = merge_root_titles(toc.get(index, []))
        title = f"卷{index} {toc_title}" if toc_title else merge_split_title(page_titles, index)
        if re.fullmatch(r"卷\d+", title):
            navigation = navigation_title(texts, pages)
            if navigation:
                title = f"卷{index} {navigation}"
        titles.append(title)
    metadata = {
        "schemaVersion": 4,
        "bookPage": book_page,
        "sourceUrl": "https://zh.wikisource.org/wiki/" + urllib.parse.quote(book_page),
        "license": "CC BY-SA 4.0",
        "licenseUrl": LICENSE_URL,
        "pages": ordered_groups,
        "rootRevision": root_revision,
        "titles": titles,
    }
    return titles, metadata


def upgrade_cached_titles(metadata: dict[str, Any], book_page: str) -> tuple[list[str], dict[str, Any]]:
    root_text, root_revision = root_wikitext(book_page)
    toc = root_titles(book_page, root_text)
    cached = [clean_markup(str(title)) for title in metadata.get("titles", [])]
    titles = []
    for index, old_title in enumerate(cached, 1):
        toc_title = merge_root_titles(toc.get(index, []))
        titles.append(f"卷{index} {toc_title}" if toc_title else old_title)
    metadata["schemaVersion"] = 4
    metadata["rootRevision"] = root_revision
    metadata["titles"] = titles
    return titles, metadata


def apply_titles(source_dir: Path, cache_dir: Path, apply: bool) -> dict[str, Any]:
    catalog = read_json(CATALOG)
    histories = catalog.get("histories", [])
    if len(histories) != len(BOOK_PAGES):
        raise TitleError("catalog and Wikisource book map must both contain 24 histories")
    report = []
    for history, book_page in zip(histories, BOOK_PAGES):
        history_id = str(history["id"])
        expected = int(history["volumes"])
        cache_path = cache_dir / f"{history_id}.json"
        cached = read_json(cache_path) if cache_path.is_file() else {}
        if cached.get("schemaVersion") == 4:
            metadata = cached
            titles = [str(title) for title in metadata.get("titles", [])]
        elif cached.get("schemaVersion") == 3:
            titles, metadata = upgrade_cached_titles(cached, book_page)
            write_json(cache_path, metadata)
        else:
            titles, metadata = fetch_book_titles(book_page, expected)
            write_json(cache_path, metadata)
        if len(titles) != expected:
            raise TitleError(f"{history_id}: {len(titles)}/{expected} cached titles")
        generic = [
            title
            for title in titles
            if not re.sub(r"^卷[^ ]+(?:\s+卷[^ ]+)?\s*", "", clean_markup(title)).strip()
        ]
        if apply:
            for filename in ("original.json", "vernacular.json"):
                path = source_dir / history_id / filename
                document = read_json(path)
                volumes = document.get("volumes", [])
                if len(volumes) != expected:
                    raise TitleError(f"{path}: {len(volumes)}/{expected} volumes")
                for volume, title in zip(volumes, titles):
                    volume["title"] = title
                if filename == "original.json":
                    document.setdefault("provenance", {})["titleSource"] = {
                        "url": metadata["sourceUrl"],
                        "license": metadata["license"],
                    }
                write_json(path, document)
        report.append(
            {
                "id": history_id,
                "volumes": expected,
                "descriptiveTitles": expected - len(generic),
                "genericTitles": len(generic),
                "first": titles[0],
                "last": titles[-1],
            }
        )
        print(f"{history_id}: {expected - len(generic)}/{expected} descriptive titles")
    return {
        "histories": len(report),
        "volumes": sum(item["volumes"] for item in report),
        "descriptiveTitles": sum(item["descriptiveTitles"] for item in report),
        "genericTitles": sum(item["genericTitles"] for item in report),
        "items": report,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--cache", type=Path, default=DEFAULT_CACHE)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        report = apply_titles(args.source, args.cache, args.apply)
        if args.report:
            write_json(args.report, report)
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0
    except (OSError, json.JSONDecodeError, TitleError) as error:
        print(f"title import failed: {error}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
