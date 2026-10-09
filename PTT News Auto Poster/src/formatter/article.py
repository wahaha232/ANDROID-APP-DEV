from __future__ import annotations

import hashlib
from typing import Iterable, Optional

from ..models import NOT_PROVIDED, DraftContent, NewsArticle, Signature

DEFAULT_TEMPLATE = (
    "[新聞] {title}\n"
    "\n"
    "新聞來源：{source}\n"
    "記者：{reporter}\n"
    "\n"
    "{body}\n"
    "\n"
    "原文網址：{url}\n"
    "\n"
    "{signature}"
)


class MissingRequiredField(ValueError):
    def __init__(self, fields: Iterable[str]):
        self.fields = sorted(fields)
        super().__init__("缺少必要欄位: " + ", ".join(self.fields))


def content_fingerprint(text: str) -> str:
    normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def _normalize(value: Optional[str], fallback: str = "") -> str:
    if value is None:
        return fallback
    text = str(value).replace("\r\n", "\n").replace("\r", "\n")
    return text.strip()


class ArticleFormatter:
    def __init__(self, template: str = DEFAULT_TEMPLATE):
        self.template = template

    def format(self, content: DraftContent) -> str:
        title = _normalize(content.title)
        body = _normalize(content.body)
        missing = []
        if not title:
            missing.append("title")
        if not body:
            missing.append("body")
        if missing:
            raise MissingRequiredField(missing)

        source = _normalize(content.source) or NOT_PROVIDED
        reporter = _normalize(content.reporter) or NOT_PROVIDED
        url = _normalize(content.url) or NOT_PROVIDED
        signature = _normalize(content.signature)

        text = self.template.format(
            title=title, source=source, reporter=reporter, body=body, url=url, signature=signature,
        )
        text = text.replace("\r\n", "\n").replace("\r", "\n")
        return text.rstrip("\n") + "\n"

    preview = format
    recompose = format

    def fingerprint(self, content: DraftContent) -> str:
        return content_fingerprint(self.format(content))

    @staticmethod
    def fingerprint_of_text(text: str) -> str:
        return content_fingerprint(text)


def resolve_signature(signatures: Iterable[Signature], name: Optional[str] = None) -> Optional[Signature]:
    items = list(signatures)
    if name:
        for item in items:
            if item.name == name:
                return item
        return None
    for item in items:
        if item.is_default:
            return item
    return items[0] if items else None


def build_draft(article: NewsArticle, signature: str = "", use_canonical: bool = False) -> DraftContent:
    url = article.raw_url
    if use_canonical and article.canonical_url:
        url = article.canonical_url
    return DraftContent(
        title=article.title,
        source=article.source,
        reporter=article.reporter or NOT_PROVIDED,
        body=article.summary,
        url=url,
        signature=signature,
    )
