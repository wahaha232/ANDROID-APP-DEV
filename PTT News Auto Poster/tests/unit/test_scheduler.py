from __future__ import annotations

import os
import tempfile
import unittest
from datetime import datetime, timedelta, timezone

from src.models import (
    DraftContent,
    ScheduleMode,
    Status,
    format_taipei,
    parse_utc_iso,
    to_utc_iso,
    utc_now,
)
from src.scheduler import (
    EVENT_AWAITING,
    EVENT_AUTO_DUE,
    EVENT_IMMEDIATE,
    ScheduleError,
    Scheduler,
    compute_approval_hash,
    draft_subject,
)
from src.storage import Database
from src import scheduler as scheduler_pkg

TAIPEI = timezone(timedelta(hours=8))


class SchedulerTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = Database(os.path.join(self.tmp.name, "sched.db"))
        self.db.initialize()
        self.sched = Scheduler(self.db)
        self.draft_id = self.db.create_draft(
            DraftContent(title="T", source="S", reporter="R", body="B", url="https://x.test/a"),
            normalized_url="https://x.test/a", fingerprint="fp-1",
        )

    def tearDown(self):
        self.db.close()
        self.tmp.cleanup()


class ScheduleModeTests(SchedulerTestCase):
    def test_immediate_sets_ready_now(self):
        task_id = self.sched.schedule(self.draft_id, ScheduleMode.IMMEDIATE)
        task = self.db.get_task(task_id)
        self.assertEqual(task["status"], Status.READY.value)
        self.assertEqual(task["schedule_mode"], ScheduleMode.IMMEDIATE.value)

    def test_at_datetime_requires_time(self):
        with self.assertRaises(ScheduleError):
            self.sched.schedule(self.draft_id, ScheduleMode.AT_DATETIME)

    def test_at_datetime_scheduled(self):
        run = utc_now() + timedelta(hours=3)
        task_id = self.sched.schedule(self.draft_id, ScheduleMode.AT_DATETIME, run_at=run)
        self.assertEqual(self.db.get_task(task_id)["status"], Status.SCHEDULED.value)

    def test_utc_stored_and_taipei_display(self):
        run = datetime(2026, 10, 10, 0, 0, 0, tzinfo=TAIPEI)
        task_id = self.sched.schedule(self.draft_id, ScheduleMode.AT_DATETIME, run_at=run)
        task = self.db.get_task(task_id)
        self.assertEqual(task["run_at_utc"], "2026-10-09T16:00:00Z")
        self.assertEqual(format_taipei(parse_utc_iso(task["run_at_utc"])), "2026-10-10 00:00:00")


class ControlTests(SchedulerTestCase):
    def test_cancel_pause_resume_pending(self):
        task_id = self.sched.schedule(self.draft_id, ScheduleMode.AT_DATETIME,
                                      run_at=utc_now() + timedelta(hours=1))
        self.assertTrue(self.sched.pause(task_id))
        self.assertEqual(self.sched.pending(), [])
        self.assertTrue(self.sched.resume(task_id))
        self.assertEqual(len(self.sched.pending()), 1)
        self.assertTrue(self.sched.cancel(task_id))
        self.assertEqual(self.db.get_task(task_id)["status"], Status.CANCELLED.value)

    def test_cancel_past_task_not_returned_as_pending(self):
        task_id = self.sched.schedule(self.draft_id, ScheduleMode.IMMEDIATE)
        self.sched.cancel(task_id)
        self.assertEqual(self.sched.pending(), [])


class TickTests(SchedulerTestCase):
    def test_due_immediate_creates_event_and_no_send(self):
        task_id = self.sched.schedule(self.draft_id, ScheduleMode.IMMEDIATE)
        events = self.sched.tick()
        self.assertEqual(len(events), 1)
        self.assertEqual(events[0]["event_type"], EVENT_IMMEDIATE)
        stored = self.db.list_events(task_id=task_id)
        self.assertEqual(len(stored), 1)
        self.assertIn("不呼叫任何 PTT 發文功能", stored[0]["payload"])

    def test_fired_due_task_does_not_refire(self):
        self.sched.schedule(self.draft_id, ScheduleMode.IMMEDIATE)
        self.assertEqual(len(self.sched.tick()), 1)
        self.assertEqual(len(self.sched.tick()), 0)

    def test_paused_task_not_fired(self):
        task_id = self.sched.schedule(self.draft_id, ScheduleMode.IMMEDIATE)
        self.sched.pause(task_id)
        self.assertEqual(len(self.sched.tick()), 0)

    def test_manual_confirm_goes_awaiting(self):
        run = utc_now() - timedelta(minutes=1)
        task_id = self.sched.schedule(self.draft_id, ScheduleMode.SCHEDULED_MANUAL_CONFIRM, run_at=run)
        self.sched.tick()
        task = self.db.get_task(task_id)
        self.assertEqual(task["status"], Status.AWAITING_CONFIRMATION.value)
        self.assertEqual(self.db.list_events(task_id=task_id)[0]["event_type"], EVENT_AWAITING)

    def test_auto_send_without_approval_goes_awaiting(self):
        run = utc_now() - timedelta(minutes=1)
        task_id = self.sched.schedule(self.draft_id, ScheduleMode.SCHEDULED_AUTO_SEND, run_at=run)
        self.sched.tick()
        self.assertEqual(self.db.get_task(task_id)["status"], Status.AWAITING_CONFIRMATION.value)

    def test_auto_send_with_approval_fires_auto_event(self):
        run = utc_now() - timedelta(minutes=1)
        task_id = self.sched.schedule(self.draft_id, ScheduleMode.SCHEDULED_AUTO_SEND, run_at=run)
        self.assertTrue(self.sched.approve(task_id))
        self.assertTrue(self.sched.auto_send_guard(self.db.get_task(task_id)))
        events = self.sched.tick()
        self.assertEqual(events[0]["event_type"], EVENT_AUTO_DUE)
        self.assertEqual(events[0]["status"], Status.READY.value)
        stored = self.db.list_events(task_id=task_id)[0]
        self.assertIn("PENDING_LOCAL_ONLY", stored["payload"])


class ApprovalInvalidationTests(SchedulerTestCase):
    def make_auto(self):
        run = utc_now() + timedelta(hours=2)
        task_id = self.sched.schedule(self.draft_id, ScheduleMode.SCHEDULED_AUTO_SEND, run_at=run)
        self.sched.approve(task_id)
        self.assertTrue(self.sched.auto_send_guard(self.db.get_task(task_id)))
        return task_id

    def test_time_change_invalidates(self):
        task_id = self.make_auto()
        self.sched.change_schedule(task_id, run_at=utc_now() + timedelta(hours=5))
        self.assertFalse(self.sched.auto_send_guard(self.db.get_task(task_id)))
        self.assertEqual(self.db.get_task(task_id)["status"], Status.AWAITING_CONFIRMATION.value)

    def test_board_change_invalidates(self):
        board = self.db.add_board("Gossiping")
        task_id = self.make_auto()
        self.sched.change_schedule(task_id, board_id=board.id)
        self.assertFalse(self.sched.auto_send_guard(self.db.get_task(task_id)))

    def test_draft_content_change_invalidates(self):
        task_id = self.make_auto()
        self.db.update_draft(self.draft_id, body="changed body", fingerprint="fp-2")
        self.assertFalse(self.sched.revalidate(task_id))
        self.assertEqual(self.db.get_task(task_id)["approved"], 0)

    def test_content_change_without_fingerprint_is_rejected(self):
        with self.assertRaises(ValueError):
            self.db.update_draft(self.draft_id, body="no fingerprint")

    def test_approve_after_due_still_fires(self):
        run = utc_now() - timedelta(minutes=1)
        task_id = self.sched.schedule(self.draft_id, ScheduleMode.SCHEDULED_AUTO_SEND, run_at=run)
        self.sched.tick()
        self.assertEqual(self.db.get_task(task_id)["status"], Status.AWAITING_CONFIRMATION.value)
        self.assertTrue(self.sched.approve(task_id))
        events = self.sched.tick()
        self.assertEqual([e["event_type"] for e in events], [EVENT_AUTO_DUE])

    def test_revalidate_terminal_and_same_status_no_crash(self):
        run = utc_now() + timedelta(hours=1)
        task_id = self.sched.schedule(self.draft_id, ScheduleMode.SCHEDULED_AUTO_SEND, run_at=run)
        self.sched.approve(task_id)
        self.db.set_task_approval(task_id, True, "stale-hash", status=Status.AWAITING_CONFIRMATION)
        self.assertFalse(self.sched.revalidate(task_id))
        self.assertEqual(self.db.get_task(task_id)["approved"], 0)

        self.sched.cancel(task_id)
        self.assertFalse(self.sched.revalidate(task_id))

    def test_approval_hash_matches_subject(self):
        run = utc_now() + timedelta(hours=1)
        task_id = self.sched.schedule(self.draft_id, ScheduleMode.SCHEDULED_AUTO_SEND, run_at=run)
        self.sched.approve(task_id)
        task = self.db.get_task(task_id)
        expected = compute_approval_hash(
            draft_subject(self.db.get_draft(self.draft_id)), None, task["run_at_utc"], None)
        self.assertEqual(task["approval_hash"], expected)


class ConcurrencyAndRecoveryTests(SchedulerTestCase):
    def test_two_schedulers_tick_single_event(self):
        self.sched.schedule(self.draft_id, ScheduleMode.IMMEDIATE)
        other = Database(self.db.path)
        try:
            events_a = self.sched.tick()
            events_b = Scheduler(other).tick()
            self.assertEqual(len(events_a) + len(events_b), 1)
        finally:
            other.close()

    def test_no_network_imports_in_scheduler(self):
        source = open(scheduler_pkg.scheduling.__file__, encoding="utf-8").read().lower()
        for forbidden in ("import socket", "import urllib", "websocket", "import requests", "ptt.cc", "post_"):
            self.assertNotIn(forbidden, source)


if __name__ == "__main__":
    unittest.main()
