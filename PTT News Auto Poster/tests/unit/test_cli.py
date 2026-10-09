from __future__ import annotations

import io
import json
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

from src.cli.main import main
from tests._support import FIXTURES

FIXTURE = str(FIXTURES / "normal_utf8.html")


class CliTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = os.path.join(self.tmp.name, "cli.db")

    def tearDown(self):
        self.tmp.cleanup()

    def run_cli(self, *args):
        out, err = io.StringIO(), io.StringIO()
        code = None
        with redirect_stdout(out), redirect_stderr(err):
            code = main(["--db", self.db, *args])
        return code, out.getvalue(), err.getvalue()

    def test_init(self):
        code, out, _ = self.run_cli("init")
        self.assertEqual(code, 0)
        self.assertIn("初始化", out)

    def test_news_parse_fixture(self):
        code, out, _ = self.run_cli("news", "parse", "--fixture", FIXTURE,
                                    "--url", "https://www.example.test/a")
        self.assertEqual(code, 0)
        data = json.loads(out)
        self.assertEqual(data["title"], "颱風來襲 全台停班停課")
        self.assertEqual(data["reporter"], "王小明")

    def test_signature_and_board(self):
        self.run_cli("signature", "add", "sig1", "-- \nhello", "--default")
        code, out, _ = self.run_cli("signature", "list")
        self.assertIn("sig1", out)
        code, out, _ = self.run_cli("board", "add", "Gossiping", "--description", "八卦板")
        self.assertIn("board_id=", out)
        code, out, _ = self.run_cli("board", "list", "--keyword", "Gossip")
        self.assertIn("Gossiping", out)

    def test_draft_create_preview_format(self):
        self.run_cli("signature", "add", "sig1", "SIGCONTENT", "--default")
        code, out, _ = self.run_cli("draft", "create", "--fixture", FIXTURE,
                                    "--url", "https://www.example.test/a")
        self.assertEqual(code, 0)
        draft_id = out.strip()
        self.assertEqual(len(draft_id), 32)

        code, out, _ = self.run_cli("draft", "preview", draft_id)
        self.assertEqual(code, 0)
        self.assertIn("[新聞] 颱風來襲 全台停班停課", out)
        self.assertIn("SIGCONTENT", out)

        code, out, err = self.run_cli("draft", "format", draft_id, "--save")
        self.assertIn("fingerprint:", err)

    def test_draft_create_duplicate_url(self):
        self.run_cli("draft", "create", "--fixture", FIXTURE, "--url", "https://www.example.test/dup")
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = main(["--db", self.db, "draft", "create", "--fixture", FIXTURE,
                         "--url", "https://www.example.test/dup"])
        self.assertEqual(code, 1)

    def test_schedule_flow(self):
        self.run_cli("draft", "create", "--fixture", FIXTURE, "--url", "https://www.example.test/s")
        _, out, _ = self.run_cli("draft", "create", "--fixture", FIXTURE,
                                 "--url", "https://www.example.test/other")
        code, out, _ = self.run_cli("schedule", "add", "--draft-id",
                                    self.latest_draft_id(), "--mode", "IMMEDIATE")
        task_id = out.strip()
        self.assertEqual(len(task_id), 32)
        code, out, _ = self.run_cli("schedule", "pending")
        self.assertIn(task_id, out)
        code, out, _ = self.run_cli("schedule", "cancel", task_id)
        self.assertIn("True", out)

    def latest_draft_id(self):
        import sqlite3
        conn = sqlite3.connect(self.db)
        row = conn.execute("SELECT draft_id FROM drafts ORDER BY created_at DESC, rowid DESC LIMIT 1").fetchone()
        conn.close()
        return row[0]

    def test_schedule_tick_event(self):
        self.run_cli("draft", "create", "--fixture", FIXTURE, "--url", "https://www.example.test/tick")
        _, out, _ = self.run_cli("schedule", "add", "--draft-id", self.latest_draft_id(),
                                 "--mode", "IMMEDIATE")
        task_id = out.strip()
        code, out, _ = self.run_cli("schedule", "tick")
        self.assertIn("IMMEDIATE_PROCESS", out)
        self.assertIn(task_id, out)


if __name__ == "__main__":
    unittest.main()
