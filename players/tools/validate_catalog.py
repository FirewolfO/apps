#!/usr/bin/env python3
"""Validate the Players remote catalog and optionally probe every HTTPS asset."""

import argparse
import json
import sys
import time
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CATALOG = ROOT / "catalog" / "catalog.json"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def probe(url):
    request = Request(url, headers={
        "Range": "bytes=0-0",
        "User-Agent": "PlayersCatalogValidator/1.0 (https://github.com/FirewolfO/apps)",
    })
    for attempt in range(4):
        try:
            with urlopen(request, timeout=30) as response:
                require(200 <= response.status < 300, f"HTTP {response.status}: {url}")
                response.read(1)
                return
        except HTTPError as error:
            if error.code not in (403, 429, 503) or attempt == 3:
                raise ValueError(f"HTTP {error.code}: {url}") from error
            time.sleep(2 ** attempt)


def validate(path, network=False):
    catalog = json.loads(path.read_text(encoding="utf-8"))
    require(catalog.get("version") == 1, "catalog version must be 1")
    items = catalog.get("items")
    require(isinstance(items, list) and 1 <= len(items) <= 500, "items must contain 1-500 entries")
    seen_ids = set()
    urls = []
    stream_count = 0
    for index, item in enumerate(items):
        prefix = f"items[{index}]"
        item_id = str(item.get("id", "")).strip()
        require(item_id and item_id not in seen_ids, f"{prefix}.id is empty or duplicated")
        seen_ids.add(item_id)
        require(str(item.get("title", "")).strip(), f"{prefix}.title is required")
        license_value = item.get("license") or {}
        require(str(license_value.get("name", "")).strip(), f"{prefix}.license.name is required")
        license_url = str(license_value.get("url", "")).strip()
        require(license_url.startswith("https://"), f"{prefix}.license.url must use HTTPS")
        poster_url = str(item.get("posterUrl", "")).strip()
        require(poster_url.startswith("https://"), f"{prefix}.posterUrl must use HTTPS")
        urls.append(poster_url)
        streams = item.get("streams")
        require(isinstance(streams, list) and streams, f"{prefix}.streams must not be empty")
        for stream_index, stream in enumerate(streams):
            stream_url = str(stream.get("url", "")).strip()
            require(stream_url.startswith("https://"), f"{prefix}.streams[{stream_index}].url must use HTTPS")
            urls.append(stream_url)
            subtitle_url = str(stream.get("subtitleUrl", "")).strip()
            if subtitle_url:
                require(subtitle_url.startswith("https://"), f"{prefix}.streams[{stream_index}].subtitleUrl must use HTTPS")
                urls.append(subtitle_url)
            stream_count += 1
    if network:
        for url in dict.fromkeys(urls):
            probe(url)
    return len(items), stream_count, len(set(urls))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("catalog", nargs="?", type=Path, default=DEFAULT_CATALOG)
    parser.add_argument("--network", action="store_true", help="probe posters, streams, and subtitles")
    args = parser.parse_args()
    try:
        items, streams, urls = validate(args.catalog, args.network)
    except Exception as error:
        print(f"catalog validation failed: {error}", file=sys.stderr)
        return 1
    print(f"catalog valid: {items} items, {streams} streams, {urls} unique assets")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
