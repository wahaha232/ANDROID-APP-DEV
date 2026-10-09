from __future__ import annotations

import hashlib
import json
from datetime import datetime
from typing import Optional

from ..models import (
    ScheduleMode,
    Status,
    can_transition,
    parse_utc_iso,
    to_utc_iso,
    utc_now,
)
from ..storage import Database, NotFoundError

EVENT_IMMEDIATE = "IMMEDIATE_PROCESS"
EVENT_DUE = "PROCESS_DUE"
EVENT_AWAITING = "AWAITING_CONFIRMATION"
EVENT_AUTO_DUE = "AUTO_SEND_DUE"

AUTO_SEND_PENDING = "PENDING_LOCAL_ONLY"


class ScheduleError(ValueError):
    pass


def draft_subject(draft: dict) -> str:
    parts = [
        draft.get("title", ""), draft.get("source", ""), draft.get("reporter", ""),
        draft.get("body", ""), draft.get("url", ""),
    ]
    return hashlib.sha256("\x1f".join(parts).encode("utf-8")).hexdigest()


def compute_approval_hash(draft_digest: str, board_id: Optional[int],
                          run_at_utc: str, signature_id: Optional[int] = None) -> str:
    parts = [
        draft_digest or "",
        "" if board_id is None else str(board_id),
        run_at_utc or "",
        "" if signature_id is None else str(signature_id),
    ]
    return hashlib.sha256("|".join(parts).encode("utf-8")).hexdigest()


class Scheduler:
    def __init__(self, db: Database):
        self.db = db

    def _draft_digest(self, task: dict) -> str:
        draft = self.db.get_draft(task["draft_id"])
        return draft_subject(draft) if draft else ""

    def _subject_hash(self, task: dict, digest: Optional[str] = None) -> str:
        if digest is None:
            digest = self._draft_digest(task)
        return compute_approval_hash(
            digest, task["board_id"], task["run_at_utc"], task["signature_id"],
        )

    def schedule(self, draft_id: str, mode: ScheduleMode, *,
                 run_at: Optional[object] = None,
                 board_id: Optional[int] = None,
                 signature_id: Optional[int] = None,
                 now: Optional[datetime] = None) -> str:
        if self.db.get_draft(draft_id) is None:
            raise NotFoundError(f"草稿不存在: {draft_id}")
        mode = ScheduleMode(mode)
        now = now or utc_now()

        if mode == ScheduleMode.IMMEDIATE:
            run_at_utc = to_utc_iso(now)
            status = Status.READY
        else:
            if run_at is None:
                raise ScheduleError(f"{mode.value} 模式必須指定排程時間")
            run_at_dt = run_at if isinstance(run_at, datetime) else parse_utc_iso(str(run_at))
            run_at_utc = to_utc_iso(run_at_dt)
            status = Status.SCHEDULED

        return self.db.create_task(
            draft_id, mode, run_at_utc,
            board_id=board_id, status=status, signature_id=signature_id,
        )

    def approve(self, task_id: str) -> bool:
        task = self.db.get_task(task_id)
        if task is None:
            raise NotFoundError(f"任務不存在: {task_id}")
        if Status(task["status"]) in (Status.CANCELLED,):
            raise ScheduleError("已取消的任務無法核准")
        approval_hash = self._subject_hash(task)
        ok = self.db.set_task_approval(task_id, True, approval_hash, status=Status.READY)
        self.db.update_task(task_id, due_fired=0)
        return ok

    def is_approved(self, task: dict, digest: Optional[str] = None) -> bool:
        return bool(task.get("approved")) and task.get("approval_hash") == self._subject_hash(task, digest)

    def auto_send_guard(self, task: dict) -> bool:
        return ScheduleMode(task["schedule_mode"]) == ScheduleMode.SCHEDULED_AUTO_SEND and self.is_approved(task)

    def revalidate(self, task_id: str) -> bool:
        task = self.db.get_task(task_id)
        if task is None:
            raise NotFoundError(f"任務不存在: {task_id}")
        if not task.get("approved"):
            return False
        if task.get("approval_hash") == self._subject_hash(task):
            return True
        self.db.clear_task_approval(task_id)
        current = Status(task["status"])
        if current not in (Status.CANCELLED, Status.FAILED) and current != Status.AWAITING_CONFIRMATION:
            if can_transition(current, Status.AWAITING_CONFIRMATION):
                self.db.update_task_status(task_id, Status.AWAITING_CONFIRMATION)
        return False

    def change_schedule(self, task_id: str, *, run_at: Optional[object] = None,
                        board_id: Optional[int] = None,
                        draft_id: Optional[str] = None,
                        signature_id: Optional[int] = None) -> bool:
        fields = {"due_fired": 0}
        if run_at is not None:
            dt = run_at if isinstance(run_at, datetime) else parse_utc_iso(str(run_at))
            fields["run_at_utc"] = to_utc_iso(dt)
        if board_id is not None:
            fields["board_id"] = board_id
        if draft_id is not None:
            fields["draft_id"] = draft_id
        if signature_id is not None:
            fields["signature_id"] = signature_id
        changed = self.db.update_task(task_id, **fields)
        self.revalidate(task_id)
        return changed

    def cancel(self, task_id: str) -> bool:
        return self.db.cancel_task(task_id)

    def pause(self, task_id: str) -> bool:
        return self.db.set_task_paused(task_id, True)

    def resume(self, task_id: str) -> bool:
        return self.db.set_task_paused(task_id, False)

    def pending(self) -> list[dict]:
        return self.db.list_pending_tasks()

    def due_tasks(self, now: Optional[datetime] = None) -> list[dict]:
        return self.db.list_due_tasks(to_utc_iso(now or utc_now()))

    def tick(self, now: Optional[datetime] = None) -> list[dict]:
        now = now or utc_now()
        created: list[dict] = []
        for task in self.due_tasks(now):
            digest = self._draft_digest(task)
            mode = ScheduleMode(task["schedule_mode"])
            if mode == ScheduleMode.SCHEDULED_MANUAL_CONFIRM:
                event_type = EVENT_AWAITING
                new_status = Status.AWAITING_CONFIRMATION
            elif mode == ScheduleMode.SCHEDULED_AUTO_SEND:
                if self.is_approved(task, digest):
                    event_type = EVENT_AUTO_DUE
                    new_status = Status.READY
                else:
                    event_type = EVENT_AWAITING
                    new_status = Status.AWAITING_CONFIRMATION
            elif mode == ScheduleMode.IMMEDIATE:
                event_type = EVENT_IMMEDIATE
                new_status = Status.READY
            else:
                event_type = EVENT_DUE
                new_status = Status.READY

            payload = json.dumps({
                "task_id": task["task_id"],
                "schedule_mode": mode.value,
                "status": AUTO_SEND_PENDING if event_type == EVENT_AUTO_DUE else new_status.value,
                "note": "本機待處理事件；Phase 2A 不呼叫任何 PTT 發文功能",
            }, ensure_ascii=False)
            event_id = self.db.fire_due_task(
                task["task_id"], event_type, payload, new_status)
            if event_id is not None:
                created.append({"event_id": event_id, "task_id": task["task_id"],
                                "event_type": event_type, "status": new_status.value})
        return created
