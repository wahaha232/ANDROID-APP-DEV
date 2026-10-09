from __future__ import annotations

import os
import tempfile
import unittest
from datetime import timedelta

from src.formatter import ArticleFormatter, build_draft
from src.models import ScheduleMode, Status, to_utc_iso, utc_now
from src.news import NewsParser
from src.scheduler import EVENT_AUTO_DUE, Scheduler
from src.storage import Database, DuplicateError
from tests._support import FakeFetcher, read_fixture


class PipelineTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = Database(os.path.join(self.tmp.name, "pipeline.db"))
        self.db.initialize()
        self.formatter = ArticleFormatter()

    def tearDown(self):
        self.db.close()
        self.tmp.cleanup()

    def _parse_to_draft(self, url="https://www.example.test/politics/1"):
        html = read_fixture("normal_utf8.html")
        article = NewsParser(fetcher=FakeFetcher(body=html.encode("utf-8"), final_url=url)).parse_url(url)
        self.assertEqual(article.parse_status, "OK")
        signature = self.db.add_signature("main", "-- \nPTTNewsBot", is_default=True) \
            if not self.db.get_default_signature() else self.db.get_default_signature()
        content = build_draft(article, signature=signature.content)
        fingerprint = self.formatter.fingerprint(content)
        draft_id = self.db.create_draft(
            content, normalized_url=article.normalized_url,
            fingerprint=fingerprint, signature_id=signature.id,
        )
        return draft_id, content, fingerprint

    def test_full_local_pipeline_no_send(self):
        draft_id, content, fingerprint = self._parse_to_draft()
        text = self.formatter.format(content)
        self.db.save_snapshot(draft_id, text, fingerprint)
        self.assertIn("[新聞] 颱風來襲 全台停班停課", text)
        self.assertEqual(self.db.list_snapshots(draft_id)[0]["fingerprint"], fingerprint)

        board = self.db.add_board("Gossiping", "八卦板")
        sched = Scheduler(self.db)
        run_at = utc_now() - timedelta(minutes=1)
        task_id = sched.schedule(draft_id, ScheduleMode.SCHEDULED_AUTO_SEND,
                                 run_at=run_at, board_id=board.id)
        self.assertTrue(sched.approve(task_id))
        events = sched.tick()
        self.assertEqual(events[0]["event_type"], EVENT_AUTO_DUE)
        self.assertEqual(self.db.get_task(task_id)["status"], Status.READY.value)
        stored = self.db.list_events(task_id=task_id)[0]
        self.assertIn("PENDING_LOCAL_ONLY", stored["payload"])
        self.assertNotIn(self.db.get_task(task_id)["status"], ("POSTED", "PUBLISHED"))

    def test_duplicate_news_blocked_in_pipeline(self):
        self._parse_to_draft(url="https://www.example.test/same")
        html = read_fixture("normal_utf8.html")
        article = NewsParser(fetcher=FakeFetcher(body=html.encode("utf-8"),
                                                 final_url="https://www.example.test/same")).parse_url(
            "https://www.example.test/same?utm_source=x")
        content = build_draft(article, signature="")
        with self.assertRaises(DuplicateError):
            self.db.create_draft(content, normalized_url=article.normalized_url,
                                 fingerprint="fp", signature_id=None)

    def test_restart_recovery(self):
        draft_id, _, _ = self._parse_to_draft(url="https://www.example.test/recover")
        sched = Scheduler(self.db)
        task_id = sched.schedule(draft_id, ScheduleMode.IMMEDIATE)
        claimed = self.db.claim_due_task("worker-1", to_utc_iso(utc_now()))
        self.assertIsNotNone(claimed)
        self.db.close()

        reopened = Database(self.db.path)
        try:
            recovered = reopened.recover_incomplete_tasks()
            self.assertEqual(recovered, [task_id])
            self.assertIsNone(reopened.get_task(task_id)["worker_id"])
        finally:
            reopened.close()
        self.db = Database(self.db.path)
        self.db.initialize()


if __name__ == "__main__":
    unittest.main()
