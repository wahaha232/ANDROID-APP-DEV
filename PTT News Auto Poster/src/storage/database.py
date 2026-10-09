from __future__ import annotations

import os
import sqlite3
import threading
from contextlib import contextmanager
from typing import Any, Optional

from ..models import (
    Board,
    DraftContent,
    ScheduleMode,
    Signature,
    Status,
    assert_transition,
    new_task_id,
    to_utc_iso,
    utc_now,
)

SCHEMA_VERSION = 1

SCHEMA = """
CREATE TABLE IF NOT EXISTS schema_meta (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS boards (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    description TEXT NOT NULL DEFAULT '',
    is_bookmarked INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS signatures (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    content TEXT NOT NULL,
    is_default INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS drafts (
    draft_id TEXT PRIMARY KEY,
    title TEXT NOT NULL DEFAULT '',
    source TEXT NOT NULL DEFAULT '',
    reporter TEXT NOT NULL DEFAULT '未提供',
    body TEXT NOT NULL DEFAULT '',
    url TEXT NOT NULL DEFAULT '',
    normalized_url TEXT NOT NULL DEFAULT '',
    signature_id INTEGER,
    status TEXT NOT NULL DEFAULT 'DRAFT',
    fingerprint TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (signature_id) REFERENCES signatures(id) ON DELETE SET NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_drafts_norm_url
    ON drafts(normalized_url) WHERE normalized_url <> '';
CREATE TABLE IF NOT EXISTS snapshots (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    draft_id TEXT NOT NULL,
    content TEXT NOT NULL,
    fingerprint TEXT NOT NULL,
    created_at TEXT NOT NULL,
    FOREIGN KEY (draft_id) REFERENCES drafts(draft_id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS tasks (
    task_id TEXT PRIMARY KEY,
    draft_id TEXT NOT NULL,
    board_id INTEGER,
    schedule_mode TEXT NOT NULL,
    run_at_utc TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'DRAFT',
    approved INTEGER NOT NULL DEFAULT 0,
    approval_hash TEXT NOT NULL DEFAULT '',
    signature_id INTEGER,
    paused INTEGER NOT NULL DEFAULT 0,
    due_fired INTEGER NOT NULL DEFAULT 0,
    worker_id TEXT,
    claimed_at_utc TEXT,
    last_error TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    FOREIGN KEY (draft_id) REFERENCES drafts(draft_id) ON DELETE CASCADE,
    FOREIGN KEY (board_id) REFERENCES boards(id) ON DELETE SET NULL,
    FOREIGN KEY (signature_id) REFERENCES signatures(id) ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks(status);
CREATE INDEX IF NOT EXISTS idx_tasks_runat ON tasks(run_at_utc);
CREATE INDEX IF NOT EXISTS idx_tasks_worker ON tasks(worker_id);
CREATE TABLE IF NOT EXISTS events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    payload TEXT NOT NULL DEFAULT '{}',
    consumed INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    FOREIGN KEY (task_id) REFERENCES tasks(task_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_events_task ON events(task_id);
"""

TERMINAL_STATUSES = (Status.CANCELLED.value,)
CLAIMABLE_STATUSES = (Status.SCHEDULED.value, Status.AWAITING_CONFIRMATION.value, Status.READY.value)

_DUE_PREDICATE = (
    "run_at_utc <= ? AND paused=0 AND due_fired=0 AND worker_id IS NULL "
    "AND status IN (?,?,?)"
)


class DatabaseError(Exception):
    pass


class DuplicateError(DatabaseError):
    pass


class NotFoundError(DatabaseError):
    pass


class Database:
    def __init__(self, path: str = ":memory:"):
        self.path = path
        if path != ":memory:":
            parent = os.path.dirname(os.path.abspath(path))
            os.makedirs(parent, exist_ok=True)
        self._lock = threading.RLock()
        self.conn = sqlite3.connect(path, timeout=30, check_same_thread=False, isolation_level=None)
        self.conn.row_factory = sqlite3.Row
        self.conn.execute("PRAGMA foreign_keys = ON")
        if path != ":memory:":
            self.conn.execute("PRAGMA journal_mode = WAL")
        self.conn.execute("PRAGMA busy_timeout = 30000")

    def close(self) -> None:
        self.conn.close()

    def __enter__(self):
        return self

    def __exit__(self, *exc_info):
        self.close()
        return False

    def initialize(self) -> None:
        with self._lock:
            self.conn.executescript(SCHEMA)
            self.conn.execute(
                "INSERT OR REPLACE INTO schema_meta(key, value) VALUES('schema_version', ?)",
                (str(SCHEMA_VERSION),),
            )

    @contextmanager
    def _write(self):
        with self._lock:
            self.conn.execute("BEGIN IMMEDIATE")
            committed = False
            try:
                yield self.conn
                self.conn.execute("COMMIT")
                committed = True
            finally:
                if not committed:
                    try:
                        self.conn.execute("ROLLBACK")
                    except sqlite3.Error:
                        pass

    @contextmanager
    def _read(self):
        with self._lock:
            yield self.conn

    def _fetchone(self, sql: str, params=()):
        with self._lock:
            return self.conn.execute(sql, params).fetchone()

    def _fetchall(self, sql: str, params=()):
        with self._lock:
            return self.conn.execute(sql, params).fetchall()

    @staticmethod
    def _now() -> str:
        return to_utc_iso(utc_now())

    # ---------------- boards ----------------

    def add_board(self, name: str, description: str = "") -> Board:
        name = name.strip()
        if not name:
            raise ValueError("看板名稱不可為空")
        now = self._now()
        try:
            with self._write() as conn:
                cur = conn.execute(
                    "INSERT INTO boards(name, description, is_bookmarked, created_at, updated_at)"
                    " VALUES(?,?,0,?,?)",
                    (name, description, now, now),
                )
                board_id = cur.lastrowid
        except sqlite3.IntegrityError as exc:
            raise DuplicateError(f"看板已存在: {name}") from exc
        return Board(id=board_id, name=name, description=description,
                     created_at=now, updated_at=now)

    def get_board(self, board_id: int) -> Optional[Board]:
        row = self._fetchone("SELECT * FROM boards WHERE id=?", (board_id,))
        return self._board(row) if row else None

    def search_boards(self, keyword: str = "") -> list[Board]:
        like = f"%{keyword.strip()}%"
        rows = self._fetchall(
            "SELECT * FROM boards WHERE name LIKE ? OR description LIKE ? ORDER BY name",
            (like, like),
        )
        return [self._board(r) for r in rows]

    def list_bookmarked_boards(self) -> list[Board]:
        rows = self._fetchall("SELECT * FROM boards WHERE is_bookmarked=1 ORDER BY name")
        return [self._board(r) for r in rows]

    def set_board_bookmark(self, board_id: int, bookmarked: bool) -> bool:
        with self._write() as conn:
            cur = conn.execute(
                "UPDATE boards SET is_bookmarked=?, updated_at=? WHERE id=?",
                (1 if bookmarked else 0, self._now(), board_id),
            )
        return cur.rowcount > 0

    def delete_board(self, board_id: int) -> bool:
        with self._write() as conn:
            cur = conn.execute("DELETE FROM boards WHERE id=?", (board_id,))
        return cur.rowcount > 0

    @staticmethod
    def _board(row: sqlite3.Row) -> Board:
        return Board(id=row["id"], name=row["name"], description=row["description"],
                     is_bookmarked=bool(row["is_bookmarked"]),
                     created_at=row["created_at"], updated_at=row["updated_at"])

    # ---------------- signatures ----------------

    def add_signature(self, name: str, content: str, is_default: bool = False) -> Signature:
        name = name.strip()
        if not name:
            raise ValueError("簽名檔名稱不可為空")
        now = self._now()
        try:
            with self._write() as conn:
                if is_default:
                    conn.execute("UPDATE signatures SET is_default=0")
                cur = conn.execute(
                    "INSERT INTO signatures(name, content, is_default, created_at, updated_at)"
                    " VALUES(?,?,?,?,?)",
                    (name, content, 1 if is_default else 0, now, now),
                )
                sig_id = cur.lastrowid
        except sqlite3.IntegrityError as exc:
            raise DuplicateError(f"簽名檔名稱已存在: {name}") from exc
        return Signature(id=sig_id, name=name, content=content, is_default=is_default,
                         created_at=now, updated_at=now)

    def update_signature(self, sig_id: int, name: Optional[str] = None,
                         content: Optional[str] = None,
                         is_default: Optional[bool] = None) -> bool:
        fields, params = [], []
        if name is not None:
            fields.append("name=?"); params.append(name.strip())
        if content is not None:
            fields.append("content=?"); params.append(content)
        if is_default is not None:
            fields.append("is_default=?"); params.append(1 if is_default else 0)
        if not fields:
            return False
        fields.append("updated_at=?"); params.append(self._now())
        params.append(sig_id)
        with self._write() as conn:
            if is_default:
                conn.execute("UPDATE signatures SET is_default=0")
            cur = conn.execute(f"UPDATE signatures SET {', '.join(fields)} WHERE id=?", params)
        return cur.rowcount > 0

    def set_default_signature(self, sig_id: int) -> bool:
        with self._write() as conn:
            conn.execute("UPDATE signatures SET is_default=0")
            cur = conn.execute(
                "UPDATE signatures SET is_default=1, updated_at=? WHERE id=?",
                (self._now(), sig_id),
            )
        return cur.rowcount > 0

    def get_signature(self, sig_id: int) -> Optional[Signature]:
        row = self._fetchone("SELECT * FROM signatures WHERE id=?", (sig_id,))
        return self._signature(row) if row else None

    def get_default_signature(self) -> Optional[Signature]:
        row = self._fetchone("SELECT * FROM signatures WHERE is_default=1 ORDER BY id LIMIT 1")
        return self._signature(row) if row else None

    def list_signatures(self) -> list[Signature]:
        rows = self._fetchall("SELECT * FROM signatures ORDER BY name")
        return [self._signature(r) for r in rows]

    def delete_signature(self, sig_id: int) -> bool:
        with self._write() as conn:
            cur = conn.execute("DELETE FROM signatures WHERE id=?", (sig_id,))
        return cur.rowcount > 0

    @staticmethod
    def _signature(row: sqlite3.Row) -> Signature:
        return Signature(id=row["id"], name=row["name"], content=row["content"],
                         is_default=bool(row["is_default"]),
                         created_at=row["created_at"], updated_at=row["updated_at"])

    # ---------------- drafts ----------------

    def create_draft(self, content: DraftContent, *, normalized_url: str = "",
                     fingerprint: str = "", signature_id: Optional[int] = None,
                     status: Status = Status.DRAFT, draft_id: Optional[str] = None) -> str:
        draft_id = draft_id or new_task_id()
        now = self._now()
        try:
            with self._write() as conn:
                conn.execute(
                    "INSERT INTO drafts(draft_id, title, source, reporter, body, url,"
                    " normalized_url, signature_id, status, fingerprint, created_at, updated_at)"
                    " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    (draft_id, content.title, content.source, content.reporter, content.body,
                     content.url, normalized_url, signature_id, Status(status).value,
                     fingerprint, now, now),
                )
        except sqlite3.IntegrityError as exc:
            existing = self.find_draft_by_normalized_url(normalized_url) if normalized_url else None
            detail = f"（既有草稿 {existing}）" if existing else ""
            raise DuplicateError(f"重複新聞 URL: {normalized_url}{detail}") from exc
        return draft_id

    def get_draft(self, draft_id: str) -> Optional[dict[str, Any]]:
        row = self._fetchone("SELECT * FROM drafts WHERE draft_id=?", (draft_id,))
        return dict(row) if row else None

    def list_drafts(self, status: Optional[Status] = None) -> list[dict[str, Any]]:
        if status is not None:
            rows = self._fetchall(
                "SELECT * FROM drafts WHERE status=? ORDER BY created_at", (Status(status).value,)
            )
        else:
            rows = self._fetchall("SELECT * FROM drafts ORDER BY created_at")
        return [dict(r) for r in rows]

    def find_draft_by_normalized_url(self, normalized_url: str) -> Optional[str]:
        if not normalized_url:
            return None
        row = self._fetchone(
            "SELECT draft_id FROM drafts WHERE normalized_url=?", (normalized_url,)
        )
        return row["draft_id"] if row else None

    def update_draft(self, draft_id: str, *, status: Optional[Status] = None,
                     fingerprint: Optional[str] = None, normalized_url: Optional[str] = None,
                     signature_id: Optional[int] = None, **fields: Any) -> bool:
        current = self.get_draft(draft_id)
        if current is None:
            raise NotFoundError(f"草稿不存在: {draft_id}")
        sets, params = [], []
        content_changed = False
        for key in ("title", "source", "reporter", "body", "url"):
            if key in fields:
                if fields[key] != current[key]:
                    content_changed = True
                sets.append(f"{key}=?"); params.append(fields[key])
        if normalized_url is not None:
            sets.append("normalized_url=?"); params.append(normalized_url)
        if signature_id is not None:
            sets.append("signature_id=?"); params.append(signature_id)
        if fingerprint is not None:
            sets.append("fingerprint=?"); params.append(fingerprint)
        elif content_changed:
            raise ValueError("修改草稿內容時必須同時提供新的 fingerprint，以避免核准失效被略過")
        if status is not None:
            assert_transition(Status(current["status"]), Status(status))
            sets.append("status=?"); params.append(Status(status).value)
        if not sets:
            return False
        sets.append("updated_at=?"); params.append(self._now())
        params.append(draft_id)
        try:
            with self._write() as conn:
                cur = conn.execute(f"UPDATE drafts SET {', '.join(sets)} WHERE draft_id=?", params)
        except sqlite3.IntegrityError as exc:
            raise DuplicateError(f"重複新聞 URL: {normalized_url}") from exc
        return cur.rowcount > 0

    def delete_draft(self, draft_id: str, force: bool = False) -> bool:
        if not force:
            row = self._fetchone(
                "SELECT COUNT(*) AS n FROM tasks WHERE draft_id=? AND status<>?",
                (draft_id, Status.CANCELLED.value),
            )
            if row and row["n"]:
                raise DatabaseError(
                    f"草稿 {draft_id} 仍有 {row['n']} 筆未取消的任務；"
                    "如需強制刪除請使用 force=True"
                )
        with self._write() as conn:
            cur = conn.execute("DELETE FROM drafts WHERE draft_id=?", (draft_id,))
        return cur.rowcount > 0

    # ---------------- snapshots ----------------

    def save_snapshot(self, draft_id: str, content: str, fingerprint: str) -> int:
        if self.get_draft(draft_id) is None:
            raise NotFoundError(f"草稿不存在: {draft_id}")
        with self._write() as conn:
            cur = conn.execute(
                "INSERT INTO snapshots(draft_id, content, fingerprint, created_at) VALUES(?,?,?,?)",
                (draft_id, content, fingerprint, self._now()),
            )
        return cur.lastrowid

    def list_snapshots(self, draft_id: str) -> list[dict[str, Any]]:
        rows = self._fetchall(
            "SELECT * FROM snapshots WHERE draft_id=? ORDER BY id", (draft_id,)
        )
        return [dict(r) for r in rows]

    # ---------------- tasks ----------------

    def create_task(self, draft_id: str, schedule_mode: ScheduleMode, run_at_utc: str,
                    *, board_id: Optional[int] = None, status: Status = Status.DRAFT,
                    signature_id: Optional[int] = None, approved: bool = False,
                    approval_hash: str = "", task_id: Optional[str] = None) -> str:
        if self.get_draft(draft_id) is None:
            raise NotFoundError(f"草稿不存在: {draft_id}")
        task_id = task_id or new_task_id()
        now = self._now()
        with self._write() as conn:
            conn.execute(
                "INSERT INTO tasks(task_id, draft_id, board_id, schedule_mode, run_at_utc,"
                " status, approved, approval_hash, signature_id, paused, created_at, updated_at)"
                " VALUES(?,?,?,?,?,?,?,?,?,0,?,?)",
                (task_id, draft_id, board_id, ScheduleMode(schedule_mode).value, run_at_utc,
                 Status(status).value, 1 if approved else 0, approval_hash, signature_id, now, now),
            )
        return task_id

    def get_task(self, task_id: str) -> Optional[dict[str, Any]]:
        row = self._fetchone("SELECT * FROM tasks WHERE task_id=?", (task_id,))
        return dict(row) if row else None

    def list_tasks(self, status: Optional[Status] = None,
                   schedule_mode: Optional[ScheduleMode] = None) -> list[dict[str, Any]]:
        clauses, params = [], []
        if status is not None:
            clauses.append("status=?"); params.append(Status(status).value)
        if schedule_mode is not None:
            clauses.append("schedule_mode=?"); params.append(ScheduleMode(schedule_mode).value)
        where = (" WHERE " + " AND ".join(clauses)) if clauses else ""
        rows = self._fetchall(
            f"SELECT * FROM tasks{where} ORDER BY run_at_utc, created_at", params
        )
        return [dict(r) for r in rows]

    def list_pending_tasks(self) -> list[dict[str, Any]]:
        placeholders = ",".join("?" for _ in TERMINAL_STATUSES)
        rows = self._fetchall(
            f"SELECT * FROM tasks WHERE status NOT IN ({placeholders}) AND paused=0"
            " ORDER BY run_at_utc",
            TERMINAL_STATUSES,
        )
        return [dict(r) for r in rows]

    def list_due_tasks(self, now_utc: str) -> list[dict[str, Any]]:
        rows = self._fetchall(
            f"SELECT * FROM tasks WHERE {_DUE_PREDICATE} ORDER BY run_at_utc",
            (now_utc, *CLAIMABLE_STATUSES),
        )
        return [dict(r) for r in rows]

    def update_task_status(self, task_id: str, status: Status, *,
                           last_error: Optional[str] = None,
                           clear_worker: bool = False) -> bool:
        task = self.get_task(task_id)
        if task is None:
            raise NotFoundError(f"任務不存在: {task_id}")
        assert_transition(Status(task["status"]), Status(status))
        sets = ["status=?", "updated_at=?"]
        params: list[Any] = [Status(status).value, self._now()]
        if last_error is not None:
            sets.append("last_error=?"); params.append(last_error)
        if clear_worker:
            sets.append("worker_id=NULL"); sets.append("claimed_at_utc=NULL")
        params.append(task_id)
        with self._write() as conn:
            cur = conn.execute(f"UPDATE tasks SET {', '.join(sets)} WHERE task_id=?", params)
        return cur.rowcount > 0

    def set_task_approval(self, task_id: str, approved: bool, approval_hash: str,
                          status: Optional[Status] = None) -> bool:
        task = self.get_task(task_id)
        if task is None:
            raise NotFoundError(f"任務不存在: {task_id}")
        sets = ["approved=?", "approval_hash=?", "updated_at=?"]
        params: list[Any] = [1 if approved else 0, approval_hash, self._now()]
        if status is not None:
            if Status(status).value != task["status"]:
                assert_transition(Status(task["status"]), Status(status))
            sets.append("status=?"); params.append(Status(status).value)
        params.append(task_id)
        with self._write() as conn:
            cur = conn.execute(f"UPDATE tasks SET {', '.join(sets)} WHERE task_id=?", params)
        return cur.rowcount > 0

    def clear_task_approval(self, task_id: str) -> bool:
        with self._write() as conn:
            cur = conn.execute(
                "UPDATE tasks SET approved=0, approval_hash='', updated_at=? WHERE task_id=?",
                (self._now(), task_id),
            )
        return cur.rowcount > 0

    def invalidate_task_approval(self, task_id: str, status: Status) -> bool:
        task = self.get_task(task_id)
        if task is None:
            raise NotFoundError(f"任務不存在: {task_id}")
        if task["status"] != Status(status).value:
            assert_transition(Status(task["status"]), Status(status))
        with self._write() as conn:
            cur = conn.execute(
                "UPDATE tasks SET approved=0, approval_hash='', status=?, updated_at=?"
                " WHERE task_id=?",
                (Status(status).value, self._now(), task_id),
            )
        return cur.rowcount > 0

    def set_task_paused(self, task_id: str, paused: bool) -> bool:
        with self._write() as conn:
            cur = conn.execute(
                "UPDATE tasks SET paused=?, updated_at=? WHERE task_id=?",
                (1 if paused else 0, self._now(), task_id),
            )
        return cur.rowcount > 0

    def cancel_task(self, task_id: str) -> bool:
        task = self.get_task(task_id)
        if task is None:
            raise NotFoundError(f"任務不存在: {task_id}")
        assert_transition(Status(task["status"]), Status.CANCELLED)
        with self._write() as conn:
            cur = conn.execute(
                "UPDATE tasks SET status=?, approved=0, approval_hash='',"
                " worker_id=NULL, claimed_at_utc=NULL, updated_at=? WHERE task_id=?",
                (Status.CANCELLED.value, self._now(), task_id),
            )
        return cur.rowcount > 0

    def fire_due_task(self, task_id: str, event_type: str, payload: str,
                      new_status: Status) -> Optional[int]:
        target = Status(new_status)
        with self._write() as conn:
            row = conn.execute("SELECT * FROM tasks WHERE task_id=?", (task_id,)).fetchone()
            if row is None or row["due_fired"]:
                return None
            current = Status(row["status"])
            if target.value != current.value:
                assert_transition(current, target)
            now = self._now()
            conn.execute(
                "UPDATE tasks SET due_fired=1, status=?, updated_at=?"
                " WHERE task_id=? AND due_fired=0",
                (target.value, now, task_id),
            )
            cur = conn.execute(
                "INSERT INTO events(task_id, event_type, payload, consumed, created_at)"
                " VALUES(?,?,?,0,?)",
                (task_id, event_type, payload, now),
            )
            return cur.lastrowid

    def claim_due_task(self, worker_id: str, now_utc: str) -> Optional[dict[str, Any]]:
        with self._write() as conn:
            row = conn.execute(
                f"SELECT task_id FROM tasks WHERE {_DUE_PREDICATE}"
                " ORDER BY run_at_utc LIMIT 1",
                (now_utc, *CLAIMABLE_STATUSES),
            ).fetchone()
            if row is None:
                return None
            task_id = row["task_id"]
            now = self._now()
            cur = conn.execute(
                "UPDATE tasks SET worker_id=?, claimed_at_utc=?, updated_at=?"
                " WHERE task_id=? AND worker_id IS NULL",
                (worker_id, now, now, task_id),
            )
            if cur.rowcount == 0:
                return None
            return dict(conn.execute("SELECT * FROM tasks WHERE task_id=?", (task_id,)).fetchone())

    def update_task(self, task_id: str, **fields: Any) -> bool:
        allowed = ("draft_id", "board_id", "schedule_mode", "run_at_utc",
                   "signature_id", "due_fired")
        sets, params = [], []
        for key in allowed:
            if key in fields:
                sets.append(f"{key}=?"); params.append(fields[key])
        if not sets:
            return False
        sets.append("updated_at=?"); params.append(self._now())
        params.append(task_id)
        with self._write() as conn:
            cur = conn.execute(f"UPDATE tasks SET {', '.join(sets)} WHERE task_id=?", params)
        return cur.rowcount > 0

    def release_task(self, task_id: str) -> bool:
        with self._write() as conn:
            cur = conn.execute(
                "UPDATE tasks SET worker_id=NULL, claimed_at_utc=NULL, updated_at=?"
                " WHERE task_id=?",
                (self._now(), task_id),
            )
        return cur.rowcount > 0

    def recover_incomplete_tasks(self) -> list[str]:
        with self._write() as conn:
            rows = conn.execute(
                "SELECT task_id FROM tasks WHERE worker_id IS NOT NULL"
            ).fetchall()
            task_ids = [r["task_id"] for r in rows]
            if task_ids:
                placeholders = ",".join("?" for _ in task_ids)
                conn.execute(
                    f"UPDATE tasks SET worker_id=NULL, claimed_at_utc=NULL, updated_at=?"
                    f" WHERE task_id IN ({placeholders})",
                    (self._now(), *task_ids),
                )
        return task_ids

    # ---------------- events ----------------

    def add_event(self, task_id: str, event_type: str, payload: str = "{}") -> int:
        if self.get_task(task_id) is None:
            raise NotFoundError(f"任務不存在: {task_id}")
        with self._write() as conn:
            cur = conn.execute(
                "INSERT INTO events(task_id, event_type, payload, consumed, created_at)"
                " VALUES(?,?,?,0,?)",
                (task_id, event_type, payload, self._now()),
            )
        return cur.lastrowid

    def list_events(self, task_id: Optional[str] = None,
                    consumed: Optional[bool] = None) -> list[dict[str, Any]]:
        clauses, params = [], []
        if task_id is not None:
            clauses.append("task_id=?"); params.append(task_id)
        if consumed is not None:
            clauses.append("consumed=?"); params.append(1 if consumed else 0)
        where = (" WHERE " + " AND ".join(clauses)) if clauses else ""
        rows = self._fetchall(f"SELECT * FROM events{where} ORDER BY id", params)
        return [dict(r) for r in rows]

    def consume_event(self, event_id: int) -> bool:
        with self._write() as conn:
            cur = conn.execute("UPDATE events SET consumed=1 WHERE id=?", (event_id,))
        return cur.rowcount > 0
