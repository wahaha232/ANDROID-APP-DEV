from __future__ import annotations

import uuid
from dataclasses import dataclass, field, asdict
from datetime import datetime, timedelta, timezone
from enum import Enum
from typing import Any, Optional

TAIPEI_TZ = timezone(timedelta(hours=8))
UTC = timezone.utc


class Status(str, Enum):
    DRAFT = "DRAFT"
    READY = "READY"
    SCHEDULED = "SCHEDULED"
    AWAITING_CONFIRMATION = "AWAITING_CONFIRMATION"
    CANCELLED = "CANCELLED"
    FAILED = "FAILED"
    NEEDS_REVIEW = "NEEDS_REVIEW"


class ScheduleMode(str, Enum):
    IMMEDIATE = "IMMEDIATE"
    AT_DATETIME = "AT_DATETIME"
    SCHEDULED_MANUAL_CONFIRM = "SCHEDULED_MANUAL_CONFIRM"
    SCHEDULED_AUTO_SEND = "SCHEDULED_AUTO_SEND"


class ParseStatus(str, Enum):
    OK = "OK"
    PARTIAL = "PARTIAL"
    BLOCKED = "BLOCKED"
    ERROR = "ERROR"


NOT_PROVIDED = "未提供"

ALLOWED_TRANSITIONS: dict[Status, frozenset[Status]] = {
    Status.DRAFT: frozenset({Status.READY, Status.NEEDS_REVIEW, Status.CANCELLED, Status.FAILED}),
    Status.READY: frozenset({
        Status.SCHEDULED, Status.DRAFT, Status.AWAITING_CONFIRMATION,
        Status.NEEDS_REVIEW, Status.CANCELLED, Status.FAILED,
    }),
    Status.SCHEDULED: frozenset({
        Status.READY, Status.AWAITING_CONFIRMATION, Status.NEEDS_REVIEW,
        Status.CANCELLED, Status.FAILED,
    }),
    Status.AWAITING_CONFIRMATION: frozenset({
        Status.READY, Status.SCHEDULED, Status.NEEDS_REVIEW, Status.CANCELLED, Status.FAILED,
    }),
    Status.NEEDS_REVIEW: frozenset({
        Status.DRAFT, Status.READY, Status.SCHEDULED, Status.CANCELLED, Status.FAILED,
    }),
    Status.CANCELLED: frozenset(),
    Status.FAILED: frozenset({Status.NEEDS_REVIEW}),
}

class InvalidStateTransition(ValueError):
    pass


def can_transition(current: Status, target: Status) -> bool:
    return target in ALLOWED_TRANSITIONS.get(Status(current), frozenset())


def assert_transition(current: Status, target: Status) -> None:
    if not can_transition(current, target):
        raise InvalidStateTransition(f"不允許的狀態轉移: {current} -> {target}")


def new_task_id() -> str:
    return uuid.uuid4().hex


def utc_now() -> datetime:
    return datetime.now(UTC)


def ensure_utc(dt: datetime) -> datetime:
    if dt.tzinfo is None:
        raise ValueError("naive datetime not allowed; specify timezone")
    return dt.astimezone(UTC)


def to_utc_iso(dt: datetime) -> str:
    return ensure_utc(dt).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_utc_iso(value: str) -> datetime:
    text = value.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    dt = datetime.fromisoformat(text)
    return ensure_utc(dt)


def to_taipei(dt: datetime) -> datetime:
    return ensure_utc(dt).astimezone(TAIPEI_TZ)


def format_taipei(dt: datetime) -> str:
    return to_taipei(dt).strftime("%Y-%m-%d %H:%M:%S")


@dataclass
class NewsArticle:
    raw_url: str
    normalized_url: str = ""
    title: str = ""
    source: str = ""
    reporter: str = NOT_PROVIDED
    summary: str = ""
    published_at: Optional[str] = None
    canonical_url: Optional[str] = None
    parse_status: str = ParseStatus.ERROR.value
    parse_error: Optional[str] = None
    site_parser: Optional[str] = None
    missing_fields: list[str] = field(default_factory=list)
    metadata: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class DraftContent:
    title: str = ""
    source: str = ""
    reporter: str = NOT_PROVIDED
    body: str = ""
    url: str = ""
    signature: str = ""

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class Signature:
    id: int
    name: str
    content: str
    is_default: bool = False
    created_at: str = ""
    updated_at: str = ""


@dataclass
class Board:
    id: int
    name: str
    description: str = ""
    is_bookmarked: bool = False
    created_at: str = ""
    updated_at: str = ""


@dataclass
class ScheduledTask:
    task_id: str
    draft_id: str
    board_id: Optional[int]
    schedule_mode: str
    run_at_utc: str
    status: str = Status.DRAFT.value
    approved: bool = False
    approval_hash: str = ""
    signature_id: Optional[int] = None
    paused: bool = False
    worker_id: Optional[str] = None
    claimed_at_utc: Optional[str] = None
    last_error: Optional[str] = None
    created_at: str = ""
    updated_at: str = ""
