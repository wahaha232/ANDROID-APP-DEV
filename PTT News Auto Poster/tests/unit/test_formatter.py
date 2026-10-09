from __future__ import annotations

import unittest

from src.formatter import (
    ArticleFormatter,
    MissingRequiredField,
    build_draft,
    content_fingerprint,
    resolve_signature,
)
from src.models import DraftContent, NewsArticle, Signature

EXPECTED = (
    "[新聞] 測試標題\n"
    "\n"
    "新聞來源：測試新聞網\n"
    "記者：王小明\n"
    "\n"
    "這是一段摘要。\n"
    "\n"
    "原文網址：https://www.example.test/a\n"
    "\n"
    "-- \n簽名檔一\n"
)


class FormatTests(unittest.TestCase):
    def setUp(self):
        self.formatter = ArticleFormatter()
        self.content = DraftContent(
            title="測試標題",
            source="測試新聞網",
            reporter="王小明",
            body="這是一段摘要。",
            url="https://www.example.test/a",
            signature="-- \n簽名檔一",
        )

    def test_fixed_format(self):
        self.assertEqual(self.formatter.format(self.content), EXPECTED)

    def test_missing_source_and_reporter_render_not_provided(self):
        content = DraftContent(title="T", body="B", url="https://x.test/a")
        out = self.formatter.format(content)
        self.assertIn("新聞來源：未提供", out)
        self.assertIn("記者：未提供", out)

    def test_missing_url_renders_not_provided(self):
        content = DraftContent(title="T", body="B")
        self.assertIn("原文網址：未提供", self.formatter.format(content))

    def test_missing_title_raises(self):
        with self.assertRaises(MissingRequiredField) as ctx:
            self.formatter.format(DraftContent(title="", body="B"))
        self.assertIn("title", ctx.exception.fields)

    def test_missing_body_raises(self):
        with self.assertRaises(MissingRequiredField) as ctx:
            self.formatter.format(DraftContent(title="T", body=""))
        self.assertIn("body", ctx.exception.fields)

    def test_no_fabrication_no_signature(self):
        content = DraftContent(title="T", source="S", reporter="R", body="B", url="U")
        out = self.formatter.format(content)
        self.assertTrue(out.rstrip().endswith("原文網址：U"))

    def test_preview_equals_format(self):
        self.assertEqual(self.formatter.preview(self.content), self.formatter.format(self.content))

    def test_recompose_after_edit(self):
        edited = DraftContent(**{**self.content.to_dict(), "title": "改過的標題"})
        self.assertIn("[新聞] 改過的標題", self.formatter.recompose(edited))


class FingerprintTests(unittest.TestCase):
    def setUp(self):
        self.formatter = ArticleFormatter()
        self.content = DraftContent(title="T", source="S", reporter="R", body="B", url="U")

    def test_reproducible(self):
        a = self.formatter.fingerprint(self.content)
        b = self.formatter.fingerprint(self.content)
        self.assertEqual(a, b)
        self.assertEqual(len(a), 64)

    def test_changes_after_edit(self):
        before = self.formatter.fingerprint(self.content)
        edited = DraftContent(title="T", source="S", reporter="R", body="B2", url="U")
        self.assertNotEqual(before, self.formatter.fingerprint(edited))

    def test_standalone_helper_matches(self):
        text = self.formatter.format(self.content)
        self.assertEqual(content_fingerprint(text), self.formatter.fingerprint_of_text(text))


class SignatureTests(unittest.TestCase):
    def test_default_signature_selected(self):
        sigs = [
            Signature(id=1, name="a", content="A"),
            Signature(id=2, name="b", content="B", is_default=True),
        ]
        self.assertEqual(resolve_signature(sigs).name, "b")

    def test_named_signature_selected(self):
        sigs = [Signature(id=1, name="a", content="A"), Signature(id=2, name="b", content="B")]
        self.assertEqual(resolve_signature(sigs, name="a").content, "A")

    def test_missing_name_returns_none(self):
        self.assertIsNone(resolve_signature([Signature(id=1, name="a", content="A")], name="z"))

    def test_empty_returns_none(self):
        self.assertIsNone(resolve_signature([]))


class BuildDraftTests(unittest.TestCase):
    def test_builds_from_article_summary_only(self):
        article = NewsArticle(
            raw_url="https://www.example.test/a",
            normalized_url="https://www.example.test/a",
            title="颱風來襲",
            source="測試新聞網",
            reporter="王小明",
            summary="摘要內容",
        )
        draft = build_draft(article, signature="-- \nsig")
        self.assertEqual(draft.body, "摘要內容")
        self.assertEqual(draft.title, "颱風來襲")
        self.assertEqual(draft.signature, "-- \nsig")

    def test_build_draft_marks_missing_reporter(self):
        article = NewsArticle(raw_url="https://x.test/a", title="T", source="S", reporter="")
        draft = build_draft(article)
        self.assertEqual(draft.reporter, "未提供")


if __name__ == "__main__":
    unittest.main()
