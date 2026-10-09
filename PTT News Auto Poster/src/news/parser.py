from __future__ import annotations

import json
from html.parser import HTMLParser
from typing import Any, Callable, Optional
from urllib.parse import urlsplit

from ..models import NOT_PROVIDED, NewsArticle, ParseStatus
from .fetcher import (
    BlockedContent,
    FetchError,
    Fetcher,
    HttpStatusError,
    NetworkError,
    ResponseTooLarge,
    SafeFetcher,
    TimeoutError as FetchTimeoutError,
    fetch_text,
)
from .urlutil import InvalidURLError, normalize_url

_BLOCK_META_KEYS = {"paywall", "content_tier", "access", "subscription"}
_BLOCK_PHRASES = (
    "paywall", "subscriber-only", "subscriber only", "subscribe to read",
    "sign in to continue", "log in to continue", "are you a robot",
    "verify you are human", "captcha", "access denied",
    "付費訂閱", "訂閱後繼續", "訂閱以閱讀", "請先登入", "登入後繼續",
    "驗證您是人類", "驗證你是真人",
)


class _Extractor(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.title_parts: list[str] = []
        self._in_title = False
        self.metas: list[tuple[str, str]] = []
        self.links: dict[str, str] = {}
        self.jsonld_blocks: list[str] = []
        self._in_jsonld = False
        self._jsonld_buf: list[str] = []
        self.time_datetimes: list[str] = []
        self.body_text_parts: list[str] = []
        self._skip_depth = 0

    def handle_starttag(self, tag: str, attrs) -> None:
        d = {k.lower(): (v or "") for k, v in attrs}
        if tag == "title":
            self._in_title = True
        elif tag == "meta":
            key = d.get("property") or d.get("name") or d.get("itemprop")
            content = d.get("content")
            if key and content is not None:
                self.metas.append((key.strip().lower(), content))
        elif tag == "link":
            rel = d.get("rel", "").lower()
            href = d.get("href")
            if rel and href:
                self.links.setdefault(rel, href)
        elif tag == "script":
            if d.get("type", "").lower().startswith("application/ld+json"):
                self._in_jsonld = True
                self._jsonld_buf = []
        elif tag == "time":
            if d.get("datetime"):
                self.time_datetimes.append(d["datetime"])
        if tag in ("script", "style"):
            self._skip_depth += 1

    def handle_endtag(self, tag: str) -> None:
        if tag == "title":
            self._in_title = False
        elif tag == "script":
            if self._in_jsonld:
                self._in_jsonld = False
                self.jsonld_blocks.append("".join(self._jsonld_buf))
        if tag in ("script", "style") and self._skip_depth:
            self._skip_depth -= 1

    def handle_data(self, data: str) -> None:
        if self._in_title:
            self.title_parts.append(data)
        if self._in_jsonld:
            self._jsonld_buf.append(data)
        elif self._skip_depth == 0:
            stripped = data.strip()
            if stripped:
                self.body_text_parts.append(stripped)

    @property
    def title(self) -> str:
        return " ".join("".join(self.title_parts).split())

    @property
    def body_text(self) -> str:
        return " ".join(self.body_text_parts)

    def meta(self, *keys: str) -> Optional[str]:
        wanted = [k.lower() for k in keys]
        for key, value in self.metas:
            if key in wanted and value.strip():
                return value.strip()
        return None


def _iter_jsonld_nodes(block: str):
    try:
        data = json.loads(block)
    except (json.JSONDecodeError, TypeError):
        return
    stack = [data]
    while stack:
        node = stack.pop()
        if isinstance(node, dict):
            if "@graph" in node and isinstance(node["@graph"], list):
                stack.extend(node["@graph"])
            yield node
        elif isinstance(node, list):
            stack.extend(node)


_ARTICLE_TYPES = {"newsarticle", "article", "reportagenewsarticle", "webpage"}


def _extract_jsonld(extractor: _Extractor) -> dict[str, Any]:
    result: dict[str, Any] = {}
    best_rank = -1
    for block in extractor.jsonld_blocks:
        for node in _iter_jsonld_nodes(block) or []:
            raw_type = node.get("@type", "")
            types = raw_type if isinstance(raw_type, list) else [raw_type]
            types = {str(t).lower() for t in types}
            if not (types & _ARTICLE_TYPES):
                continue
            rank = 2 if (types & {"newsarticle", "reportagenewsarticle"}) else 1
            if rank <= best_rank:
                continue
            best_rank = rank
            headline = node.get("headline") or node.get("name")
            description = node.get("description")
            date = node.get("datePublished") or node.get("dateCreated")
            author = node.get("author")
            publisher = node.get("publisher")
            url = None
            main = node.get("mainEntityOfPage")
            if isinstance(main, dict):
                url = main.get("@id") or main.get("url")
            elif isinstance(main, str):
                url = main
            result = {
                "headline": headline if isinstance(headline, str) else None,
                "description": description if isinstance(description, str) else None,
                "date": date if isinstance(date, str) else None,
                "author": _author_name(author),
                "publisher": _publisher_name(publisher),
                "url": url,
            }
    return result


def _author_name(author) -> Optional[str]:
    if isinstance(author, str):
        return _clean_reporter(author)
    if isinstance(author, dict):
        name = author.get("name")
        return _clean_reporter(name) if isinstance(name, str) else None
    if isinstance(author, list):
        names = [_author_name(a) for a in author]
        names = [n for n in names if n]
        return "、".join(names) if names else None
    return None


def _publisher_name(publisher) -> Optional[str]:
    if isinstance(publisher, dict):
        name = publisher.get("name")
        return name.strip() if isinstance(name, str) and name.strip() else None
    if isinstance(publisher, str):
        return publisher.strip() or None
    return None


def _clean_reporter(value: Optional[str]) -> Optional[str]:
    if not value:
        return None
    text = value.strip()
    if not text or text.startswith("http://") or text.startswith("https://") or text.startswith("@"):
        return None
    for prefix in ("記者", "文/", "文／", "撰文", "作者", "by ", "By "):
        if text.startswith(prefix):
            text = text[len(prefix):].strip()
    return text or None


SITE_PARSERS: dict[str, Callable[[str, _Extractor], dict[str, Any]]] = {}


def register_site_parser(domain_suffix: str, parser: Callable[[str, _Extractor], dict[str, Any]]) -> None:
    SITE_PARSERS[domain_suffix.lower()] = parser


def get_site_parser(host: str):
    host = (host or "").lower()
    for suffix, parser in SITE_PARSERS.items():
        if host == suffix or host.endswith("." + suffix):
            return suffix, parser
    return None, None


def _detect_block(extractor: _Extractor, title: str, summary: str) -> Optional[str]:
    for key, value in extractor.metas:
        if key in _BLOCK_META_KEYS:
            low = value.lower()
            if low in ("true", "1", "yes", "locked", "premium", "subscriber"):
                return f"偵測到付費/受限標記 meta {key}={value}"
    haystack = f"{title} {summary}".lower()
    for phrase in _BLOCK_PHRASES:
        if phrase in haystack:
            return f"內容疑似付費牆/登入/CAPTCHA：出現「{phrase}」"
    return None


class NewsParser:
    def __init__(self, fetcher: Optional[Fetcher] = None):
        self.fetcher = fetcher or SafeFetcher()

    def parse_url(self, url: str) -> NewsArticle:
        article = NewsArticle(raw_url=url)
        try:
            article.normalized_url = normalize_url(url)
        except InvalidURLError as exc:
            article.parse_status = ParseStatus.ERROR.value
            article.parse_error = f"URL 無效: {exc}"
            return article
        try:
            result = self.fetcher.fetch(url)
            html = fetch_text(result)
        except BlockedContent as exc:
            article.parse_status = ParseStatus.BLOCKED.value
            article.parse_error = str(exc)
            return article
        except FetchTimeoutError as exc:
            article.parse_status = ParseStatus.ERROR.value
            article.parse_error = f"逾時: {exc}"
            return article
        except ResponseTooLarge as exc:
            article.parse_status = ParseStatus.ERROR.value
            article.parse_error = f"回應過大: {exc}"
            return article
        except HttpStatusError as exc:
            article.parse_status = ParseStatus.ERROR.value
            article.parse_error = f"HTTP 錯誤: {exc}"
            return article
        except NetworkError as exc:
            article.parse_status = ParseStatus.ERROR.value
            article.parse_error = f"網路錯誤: {exc}"
            return article
        except FetchError as exc:
            article.parse_status = ParseStatus.ERROR.value
            article.parse_error = f"取得失敗: {exc}"
            return article
        except Exception as exc:  # pragma: no cover - defensive
            article.parse_status = ParseStatus.ERROR.value
            article.parse_error = f"未預期錯誤: {exc}"
            return article
        article.normalized_url = normalize_url(result.final_url)
        return self.parse_html(html, url, final_url=result.final_url)

    def parse_html(self, html: str, url: str, final_url: Optional[str] = None) -> NewsArticle:
        if isinstance(html, bytes):  # pragma: no cover - convenience
            html = html.decode("utf-8", "replace")
        base_url = final_url or url
        article = NewsArticle(raw_url=url)
        try:
            article.normalized_url = normalize_url(base_url)
        except InvalidURLError:
            article.normalized_url = base_url

        extractor = _Extractor()
        extractor.feed(html)
        extractor.close()

        site_name, site_parser = get_site_parser(urlsplit(base_url).hostname or "")
        ld = _extract_jsonld(extractor)
        overrides: dict[str, Any] = {}
        if site_parser:
            try:
                overrides = site_parser(base_url, extractor) or {}
            except Exception:  # pragma: no cover - defensive
                overrides = {}
        article.site_parser = site_name

        title = (
            overrides.get("title")
            or ld.get("headline")
            or extractor.meta("og:title", "twitter:title")
            or extractor.title
        )
        if title:
            title = " ".join(str(title).split())
        summary = (
            overrides.get("summary")
            or ld.get("description")
            or extractor.meta("og:description", "description", "twitter:description")
            or ""
        )
        source = (
            overrides.get("source")
            or ld.get("publisher")
            or extractor.meta("og:site_name", "application-name", "source")
            or ""
        )
        reporter = (
            overrides.get("reporter")
            or ld.get("author")
            or _clean_reporter(extractor.meta("author", "article:author", "twitter:creator"))
            or NOT_PROVIDED
        )
        published = (
            overrides.get("published_at")
            or ld.get("date")
            or extractor.meta("article:published_time", "datePublished", "pubdate", "date", "dc.date")
            or (extractor.time_datetimes[0] if extractor.time_datetimes else None)
        )

        article.title = title or ""
        article.source = source or ""
        article.reporter = reporter or NOT_PROVIDED
        article.summary = summary or ""
        article.published_at = published
        article.canonical_url = overrides.get("canonical_url") or extractor.links.get("canonical") or ld.get("url")

        block_reason = _detect_block(extractor, article.title, article.summary)
        missing = []
        if not article.title:
            missing.append("title")
        if not article.source:
            missing.append("source")
        if not article.summary:
            missing.append("summary")
        article.missing_fields = missing

        if block_reason:
            article.parse_status = ParseStatus.BLOCKED.value
            article.parse_error = block_reason
        elif not article.title:
            article.parse_status = ParseStatus.ERROR.value
            article.parse_error = "缺少標題，無法組成文章"
        elif "title" in missing or "source" in missing or "summary" in missing:
            article.parse_status = ParseStatus.PARTIAL.value
            article.parse_error = "缺少欄位: " + ", ".join(missing)
        else:
            article.parse_status = ParseStatus.OK.value
            article.parse_error = None
        return article
