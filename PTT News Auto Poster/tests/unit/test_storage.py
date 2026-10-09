from __future__ import annotations

import os
import tempfile
import unittest
from datetime import timedelta

from src.models import (
    DraftContent,
    InvalidStateTransition,
    ScheduleMode,
    Status,
    utc_now,
    to_utc_iso,
)
from src.storage import Database, DuplicateError, NotFoundError


def past_iso(minutes=5):
    return to_utc_iso(utc_now() - timedelta(minutes=minutes))


def future_iso(minutes=60):
    return to_utc_iso(utc_now() + timedelta(minutes=minutes))


class StorageTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db_path = os.path.join(self.tmp.name, "test.db")
        self.db = Database(self.db_path)
        self.db.initialize()

    def tearDown(self):
        self.db.close()
        self.tmp.cleanup()

    def make_draft(self, url="https://www.example.test/a?utm_source=x"):
        content = DraftContent(title="T", source="S", reporter="R", body="B", url=url)
        return self.db.create_draft(content, normalized_url=url.split("?")[0], fingerprint="fp1")


class BoardTests(StorageTestCase):
    def test_crud_and_search(self):
        b = self.db.add_board("Gossiping", "八卦板")
        self.db.add_board("Baseball", "棒球板")
        self.assertEqual(self.db.get_board(b.id).name, "Gossiping")
        self.assertEqual(len(self.db.search_boards("棒球")), 1)
        self.assertEqual(len(self.db.search_boards("")), 2)
        self.assertTrue(self.db.set_board_bookmark(b.id, True))
        self.assertEqual(len(self.db.list_bookmarked_boards()), 1)
        self.assertTrue(self.db.delete_board(b.id))
        self.assertIsNone(self.db.get_board(b.id))

    def test_duplicate_board(self):
        self.db.add_board("Gossiping")
        with self.assertRaises(DuplicateError):
            self.db.add_board("Gossiping")

    def test_failed_write_rolls_back_and_connection_usable(self):
        self.db.add_board("A")
        with self.assertRaises(DuplicateError):
            self.db.add_board("A")
        self.db.add_board("B")
        self.assertEqual(len(self.db.search_boards("")), 2)


class SignatureFkTests(StorageTestCase):
    def test_delete_signature_nulls_task_reference(self):
        sig = self.db.add_signature("sig", "content")
        draft_id = self.make_draft()
        task_id = self.db.create_task(draft_id, ScheduleMode.IMMEDIATE, past_iso(),
                                      signature_id=sig.id)
        self.assertEqual(self.db.get_task(task_id)["signature_id"], sig.id)
        self.db.delete_signature(sig.id)
        self.assertIsNone(self.db.get_task(task_id)["signature_id"])


class SignatureTests(StorageTestCase):
    def test_crud_and_default(self):
        s1 = self.db.add_signature("sig-a", "AAA")
        s2 = self.db.add_signature("sig-b", "BBB", is_default=True)
        self.assertEqual(self.db.get_default_signature().name, "sig-b")
        self.assertTrue(self.db.set_default_signature(s1.id))
        self.assertEqual(self.db.get_default_signature().name, "sig-a")
        self.assertTrue(self.db.update_signature(s2.id, content="BBBB"))
        self.assertEqual(self.db.get_signature(s2.id).content, "BBBB")
        self.assertEqual(len(self.db.list_signatures()), 2)
        self.assertTrue(self.db.delete_signature(s2.id))

    def test_duplicate_name(self):
        self.db.add_signature("sig", "A")
        with self.assertRaises(DuplicateError):
            self.db.add_signature("sig", "B")


class DraftTests(StorageTestCase):
    def test_create_and_get(self):
        draft_id = self.make_draft()
        row = self.db.get_draft(draft_id)
        self.assertEqual(row["title"], "T")
        self.assertEqual(row["status"], Status.DRAFT.value)

    def test_duplicate_normalized_url_blocked(self):
        self.make_draft(url="https://www.example.test/dup")
        content = DraftContent(title="T2", source="S", reporter="R", body="B2",
                               url="https://www.example.test/dup")
        with self.assertRaises(DuplicateError):
            self.db.create_draft(content, normalized_url="https://www.example.test/dup")

    def test_find_by_normalized_url(self):
        draft_id = self.make_draft(url="https://www.example.test/find")
        self.assertEqual(
            self.db.find_draft_by_normalized_url("https://www.example.test/find"), draft_id
        )

    def test_invalid_status_transition_rejected_and_rolled_back(self):
        draft_id = self.make_draft()
        self.db.update_draft(draft_id, status=Status.CANCELLED)
        with self.assertRaises(InvalidStateTransition):
            self.db.update_draft(draft_id, status=Status.READY)
        self.assertEqual(self.db.get_draft(draft_id)["status"], Status.CANCELLED.value)

    def test_snapshot(self):
        draft_id = self.make_draft()
        self.db.save_snapshot(draft_id, "content-1", "fp-1")
        self.db.save_snapshot(draft_id, "content-2", "fp-2")
        snaps = self.db.list_snapshots(draft_id)
        self.assertEqual([s["fingerprint"] for s in snaps], ["fp-1", "fp-2"])

    def test_snapshot_unknown_draft(self):
        with self.assertRaises(NotFoundError):
            self.db.save_snapshot("missing", "c", "f")


class TaskTests(StorageTestCase):
    def test_unique_task_ids(self):
        draft_id = self.make_draft()
        t1 = self.db.create_task(draft_id, ScheduleMode.IMMEDIATE, past_iso())
        t2 = self.db.create_task(draft_id, ScheduleMode.IMMEDIATE, past_iso())
        self.assertNotEqual(t1, t2)
        self.assertEqual(len(t1), 32)

    def test_status_transition_validation(self):
        draft_id = self.make_draft()
        task_id = self.db.create_task(draft_id, ScheduleMode.AT_DATETIME, future_iso(),
                                      status=Status.SCHEDULED)
        self.db.update_task_status(task_id, Status.CANCELLED)
        with self.assertRaises(InvalidStateTransition):
            self.db.update_task_status(task_id, Status.READY)

    def test_pause_resume_pending(self):
        draft_id = self.make_draft()
        task_id = self.db.create_task(draft_id, ScheduleMode.AT_DATETIME, future_iso(),
                                      status=Status.SCHEDULED)
        self.assertTrue(self.db.set_task_paused(task_id, True))
        self.assertEqual(self.db.list_pending_tasks(), [])
        self.assertTrue(self.db.set_task_paused(task_id, False))
        self.assertEqual(len(self.db.list_pending_tasks()), 1)

    def test_approval_set_and_invalidate(self):
        draft_id = self.make_draft()
        task_id = self.db.create_task(draft_id, ScheduleMode.SCHEDULED_AUTO_SEND, future_iso(),
                                      status=Status.SCHEDULED)
        self.db.set_task_approval(task_id, True, "hash-1", status=Status.READY)
        task = self.db.get_task(task_id)
        self.assertEqual(task["approved"], 1)
        self.db.invalidate_task_approval(task_id, Status.AWAITING_CONFIRMATION)
        task = self.db.get_task(task_id)
        self.assertEqual(task["approved"], 0)
        self.assertEqual(task["approval_hash"], "")
        self.assertEqual(task["status"], Status.AWAITING_CONFIRMATION.value)

    def test_events(self):
        draft_id = self.make_draft()
        task_id = self.db.create_task(draft_id, ScheduleMode.IMMEDIATE, past_iso())
        eid = self.db.add_event(task_id, "IMMEDIATE_PROCESS")
        self.assertEqual(len(self.db.list_events(consumed=False)), 1)
        self.assertTrue(self.db.consume_event(eid))
        self.assertEqual(self.db.list_events(consumed=False), [])


class ConcurrencyTests(StorageTestCase):
    def test_two_connections_claim_same_task(self):
        draft_id = self.make_draft()
        self.db.create_task(draft_id, ScheduleMode.IMMEDIATE, past_iso(), status=Status.READY)

        other = Database(self.db_path)
        try:
            first = self.db.claim_due_task("worker-a", to_utc_iso(utc_now()))
            second = other.claim_due_task("worker-b", to_utc_iso(utc_now()))
            self.assertIsNotNone(first)
            self.assertIsNone(second)
            self.assertEqual(first["worker_id"], "worker-a")
        finally:
            other.close()

    def test_recover_incomplete_tasks(self):
        draft_id = self.make_draft()
        self.db.create_task(draft_id, ScheduleMode.IMMEDIATE, past_iso(), status=Status.READY)
        claimed = self.db.claim_due_task("worker-x", to_utc_iso(utc_now()))
        self.assertIsNotNone(claimed)
        recovered = self.db.recover_incomplete_tasks()
        self.assertEqual(recovered, [claimed["task_id"]])
        self.assertIsNone(self.db.get_task(claimed["task_id"])["worker_id"])


class PersistenceTests(StorageTestCase):
    def test_restart_persistence(self):
        draft_id = self.make_draft()
        self.db.add_board("Gossiping")
        self.db.close()

        reopened = Database(self.db_path)
        try:
            self.assertIsNotNone(reopened.get_draft(draft_id))
            self.assertEqual(len(reopened.search_boards("Gossiping")), 1)
        finally:
            reopened.close()
        self.db = Database(self.db_path)
        self.db.initialize()


if __name__ == "__main__":
    unittest.main()
