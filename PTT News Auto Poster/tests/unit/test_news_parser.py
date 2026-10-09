from __future__ import annotations

import unittest
from urllib.parse import urlsplit

from src.models import ParseStatus
from src.news.fetcher import (
    BlockedContent,
    HttpStatusError,
    ResponseTooLarge,
    TimeoutError as FetchTimeoutError,
    fetch_text,
)
from src.news.parser import NewsParser, get_site_parser, register_site_parser
from tests._support import FakeFetcher, read_fixture, read_fixture_bytes


class NewsParserMetadataTests(unittest.TestCase):
    def setUp(self):
        self.parser = NewsParser(fetcher=None)

    def parse_fixture(self, name, url="https://www.example.test/politics/12345", encoding="utf-8"):
        html = read_fixture(name, encoding)
        return self.parser.parse_html(html, url)

    def test_normal_metadata(self):
        article = self.parse_fixture("normal_utf8.html")
        self.assertEqual(article.parse_status, ParseStatus.OK.value)
        self.assertEqual(article.title, "颱風來襲 全台停班停課")
        self.assertEqual(article.source, "測試新聞網")
        self.assertEqual(article.reporter, "王小明")
        self.assertIn("颱風", article.summary)
        self.assertEqual(article.published_at, "2026-10-09T08:30:00+08:00")

    def test_jsonld_only(self):
        article = self.parse_fixture("jsonld_only.html")
        self.assertEqual(article.parse_status, ParseStatus.OK.value)
        self.assertEqual(article.title, "只用 JSON-LD 的新聞")
        self.assertEqual(article.source, "純結構化新聞網")
        self.assertEqual(article.reporter, "陳大文、林小美")
        self.assertEqual(article.canonical_url, "https://www.example.test/tech/999")

    def test_missing_fields_marks_partial_and_reporter_not_provided(self):
        article = self.parse_fixture("missing_fields.html")
        self.assertEqual(article.parse_status, ParseStatus.PARTIAL.value)
        self.assertEqual(article.reporter, "未提供")
        self.assertIn("source", article.missing_fields)
        self.assertIn("summary", article.missing_fields)
        self.assertEqual(article.title, "僅有標題的新聞")

    def test_no_title_is_error(self):
        article = self.parse_fixture("no_title.html")
        self.assertEqual(article.parse_status, ParseStatus.ERROR.value)
        self.assertIn("title", article.missing_fields)
        self.assertIsNotNone(article.parse_error)

    def test_paywall_is_blocked(self):
        article = self.parse_fixture("paywall.html")
        self.assertEqual(article.parse_status, ParseStatus.BLOCKED.value)
        self.assertIn("付費", article.parse_error)

    def test_reporter_never_guessed_from_body(self):
        html = "<html><head><title>T</title></head><body>記者張三報導</body></html>"
        article = self.parser.parse_html(html, "https://example.test/x")
        self.assertEqual(article.reporter, "未提供")

    def test_normalized_url_strips_tracking(self):
        url = "https://www.example.test/politics/12345?utm_source=homepage&fbclid=abc"
        article = self.parser.parse_html(read_fixture("normal_utf8.html"), url)
        self.assertEqual(article.normalized_url, "https://www.example.test/politics/12345")

    def test_site_parser_extension_registry(self):
        def custom(url, extractor):
            return {"title": "站別 Parser 覆寫標題", "source": "站別來源"}
        register_site_parser("example.test", custom)
        try:
            self.assertIsNotNone(get_site_parser("www.example.test")[1])
            article = self.parser.parse_html(read_fixture("normal_utf8.html"),
                                             "https://www.example.test/politics/12345")
            self.assertEqual(article.title, "站別 Parser 覆寫標題")
            self.assertEqual(article.source, "站別來源")
        finally:
            from src.news import parser as parser_mod
            parser_mod.SITE_PARSERS.pop("example.test", None)


class NewsParserEncodingTests(unittest.TestCase):
    def test_big5_content(self):
        raw = read_fixture_bytes("normal_big5.html")
        text = raw.decode("big5")
        self.assertIn("中華隊", text)
        parser = NewsParser()
        article = parser.parse_html(text, "https://www.bighua.test/sports/1")
        self.assertEqual(article.parse_status, ParseStatus.OK.value)
        self.assertEqual(article.title, "中華隊奪冠 舉國歡騰")
        self.assertEqual(article.source, "大華晚報")
        self.assertEqual(article.reporter, "李大同")

    def test_utf8_traditional_chinese(self):
        article = NewsParser().parse_html(read_fixture("normal_utf8.html"),
                                          "https://www.example.test/a")
        self.assertIn("颱風", article.title)


class NewsParserFetchErrorTests(unittest.TestCase):
    def test_fetch_timeout_sets_error_status(self):
        parser = NewsParser(fetcher=FakeFetcher(exc=FetchTimeoutError("boom")))
        article = parser.parse_url("https://example.test/a")
        self.assertEqual(article.parse_status, ParseStatus.ERROR.value)
        self.assertIn("逾時", article.parse_error)

    def test_fetch_too_large_sets_error_status(self):
        parser = NewsParser(fetcher=FakeFetcher(exc=ResponseTooLarge("big")))
        article = parser.parse_url("https://example.test/a")
        self.assertEqual(article.parse_status, ParseStatus.ERROR.value)
        self.assertIn("過大", article.parse_error)

    def test_fetch_blocked_sets_blocked_status(self):
        parser = NewsParser(fetcher=FakeFetcher(exc=BlockedContent("403")))
        article = parser.parse_url("https://example.test/a")
        self.assertEqual(article.parse_status, ParseStatus.BLOCKED.value)

    def test_fetch_http_error_sets_error_status(self):
        parser = NewsParser(fetcher=FakeFetcher(exc=HttpStatusError(500)))
        article = parser.parse_url("https://example.test/a")
        self.assertEqual(article.parse_status, ParseStatus.ERROR.value)

    def test_invalid_url_short_circuits(self):
        parser = NewsParser(fetcher=FakeFetcher())
        article = parser.parse_url("not-a-url")
        self.assertEqual(article.parse_status, ParseStatus.ERROR.value)

    def test_parse_url_uses_fetcher_and_final_url(self):
        html = read_fixture("normal_utf8.html")
        fetcher = FakeFetcher(body=html.encode("utf-8"),
                              final_url="https://www.example.test/politics/12345?fbclid=x")
        article = NewsParser(fetcher=fetcher).parse_url("http://example.test/short")
        self.assertEqual(article.parse_status, ParseStatus.OK.value)
        self.assertEqual(article.normalized_url, "https://www.example.test/politics/12345")
        self.assertEqual(fetcher.calls, ["http://example.test/short"])


if __name__ == "__main__":
    unittest.main()
