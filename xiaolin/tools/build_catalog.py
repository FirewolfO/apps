#!/usr/bin/env python3
"""Build the in-app catalog from the official Xiaolin site navigation."""

import json
import re
from datetime import date
from pathlib import Path
from urllib.parse import urljoin, urlparse

import requests
from bs4 import BeautifulSoup


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "app/src/main/assets/catalog.json"

SESSION = requests.Session()
SESSION.headers.update({"User-Agent": "XiaolinStudyCatalogBuilder/1.0"})


def soup_for(url):
    response = SESSION.get(url, timeout=30)
    response.raise_for_status()
    response.encoding = "utf-8"
    return BeautifulSoup(response.text, "html.parser")


def clean_section_title(value):
    return re.sub(r"^\s*\d+\s*[|｜]\s*", "", value).strip()


def absolute_url(base_url, href):
    return urljoin(base_url, href).split("#", 1)[0]


def article(title, url):
    return {"title": title.strip(), "url": url.rstrip("/") if url.count("/") > 2 else url}


def vuepress_sections(url):
    soup = soup_for(url)
    sections = []
    for group in soup.select("section.sidebar-group.depth-0"):
        heading = group.select_one(".sidebar-heading")
        title = clean_section_title(heading.get_text(" ", strip=True) if heading else "其他内容")
        entries = []
        seen = set()
        for link in group.select("a.sidebar-link[href]"):
            href = link.get("href", "")
            if not href or "#" in href:
                continue
            target = absolute_url(url, href)
            if target in seen:
                continue
            seen.add(target)
            entries.append(article(link.get_text(" ", strip=True), target))
        if entries:
            sections.append({"title": title, "articles": entries})
    return sections


def vitepress_sections(url):
    soup = soup_for(url)
    sections = []
    base_path = urlparse(url).path
    for group in soup.select(".vp-sidebar-group"):
        heading = group.select_one(".vp-sidebar-title")
        title = heading.get_text(" ", strip=True) if heading else "其他内容"
        entries = []
        seen = set()
        for link in group.select("a[href]"):
            target = absolute_url(url, link.get("href", ""))
            if not urlparse(target).path.startswith(base_path) or target in seen:
                continue
            seen.add(target)
            entries.append(article(link.get_text(" ", strip=True), target))
        if entries:
            sections.append({"title": title, "articles": entries})
    return sections


def book(title, description, home_url, sections):
    return {
        "title": title,
        "description": description,
        "homeUrl": home_url,
        "sections": sections,
    }


def illustrated_books():
    definitions = [
        ("图解网络", "网络基础、HTTP、TCP、IP 与实战", "https://www.xiaolincoding.com/network/", "vue"),
        ("图解操作系统", "硬件、内存、进程、文件与 I/O", "https://www.xiaolincoding.com/os/", "vue"),
        ("图解 MySQL", "索引、事务、锁、日志与架构", "https://www.xiaolincoding.com/mysql/", "vue"),
        ("图解 Redis", "数据结构、持久化、高可用与缓存", "https://www.xiaolincoding.com/redis/", "vue"),
        ("图解 Agent", "Agent、RAG 与工程方法论", "https://xiaolinnote.com/agent/", "vite"),
        ("图解 Claude Code", "使用技巧、源码解析与行业观察", "https://xiaolinnote.com/claudecode/", "vite"),
    ]
    result = []
    for title, description, url, kind in definitions:
        sections = vuepress_sections(url) if kind == "vue" else vitepress_sections(url)
        result.append(book(title, description, url, sections))
    return result


def backend_books():
    interview_url = "https://www.xiaolincoding.com/interview/"
    sections = vuepress_sections(interview_url)

    def choose(*indexes):
        return [sections[index] for index in indexes]

    result = [
        book("Java 后端面试题", "Java 核心、数据库、计算机基础与架构", interview_url,
             choose(0, 3, 4, 5, 6, 8)),
        book("Golang 面试题", "Golang 高频面试知识点", "https://www.xiaolincoding.com/interview/golang.html", choose(2)),
        book("C++ 面试题", "C++ 高频面试知识点", "https://www.xiaolincoding.com/interview/cpp.html", choose(1)),
        book("测试开发面试题", "业务测试、自动化与性能测试", "https://www.xiaolincoding.com/interview/test_dev.html", choose(7)),
    ]
    backend_url = "https://www.xiaolincoding.com/backend_interview/"
    result.append(book("大厂后端面经", "互联网、手机、通信、新能源与银行面经", backend_url,
                       vuepress_sections(backend_url)))
    return result


def ai_books():
    url = "https://xiaolinnote.com/ai/"
    soup = soup_for(url)
    main = soup.select_one("main") or soup
    descriptions = {
        "Agent 面试题": "架构、规划、记忆与多 Agent 协作",
        "RAG 面试题": "切分、向量检索、召回与评估",
        "LLM 工具调用面试题": "Function Calling、MCP 与工具编排",
        "大模型工程面试题": "模型原理、推理、微调与工程实践",
        "LangChain 框架面试题": "组件、链、Agent 与工程设计",
    }
    result = []
    for heading in main.select("h2"):
        raw_title = heading.get_text(" ", strip=True)
        title = raw_title.replace("面试专题", "面试题").replace("  ", " ").strip()
        if title not in descriptions:
            continue
        entries = []
        seen = set()
        sibling = heading.find_next_sibling()
        while sibling is not None and sibling.name != "h2":
            for link in sibling.select("a[href]"):
                target = absolute_url(url, link.get("href", ""))
                if not urlparse(target).path.startswith("/ai/") or target in seen:
                    continue
                seen.add(target)
                entries.append(article(link.get_text(" ", strip=True), target))
            sibling = sibling.find_next_sibling()
        if not entries:
            raise RuntimeError("No articles found for " + title)
        result.append(book(title, descriptions[title], entries[0]["url"], [
            {"title": "全部问答", "articles": entries}
        ]))
    return result


def article_count(value):
    return sum(len(section["articles"]) for section in value["sections"])


def validate(catalog):
    expected_groups = ["图解系列", "后端八股面试系列", "AI Agent 面试八股系列"]
    actual_groups = [group["title"] for group in catalog["groups"]]
    if actual_groups != expected_groups:
        raise RuntimeError("Unexpected groups: " + repr(actual_groups))
    all_urls = []
    for group in catalog["groups"]:
        if not group["books"]:
            raise RuntimeError("Empty group: " + group["title"])
        for item in group["books"]:
            if article_count(item) == 0:
                raise RuntimeError("Empty book: " + item["title"])
            all_urls.extend(
                entry["url"]
                for section in item["sections"]
                for entry in section["articles"]
            )
    if len(all_urls) < 250:
        raise RuntimeError("Catalog unexpectedly small: " + str(len(all_urls)))


def main():
    catalog = {
        "generatedAt": date.today().isoformat(),
        "groups": [
            {
                "title": "图解系列",
                "description": "按知识体系学习小林图解内容",
                "books": illustrated_books(),
            },
            {
                "title": "后端八股面试系列",
                "description": "按语言、岗位和公司整理面试内容",
                "books": backend_books(),
            },
            {
                "title": "AI Agent 面试八股系列",
                "description": "Agent、RAG、工具调用与大模型工程",
                "books": ai_books(),
            },
        ],
    }
    validate(catalog)
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    total = 0
    for group in catalog["groups"]:
        count = sum(article_count(item) for item in group["books"])
        total += count
        print(f"{group['title']}: {len(group['books'])} 个专题，{count} 篇")
    print(f"合计: {total} 篇 -> {OUTPUT}")


if __name__ == "__main__":
    main()
