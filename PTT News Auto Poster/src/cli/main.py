from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from ..formatter import ArticleFormatter, MissingRequiredField, build_draft, resolve_signature
from ..models import DraftContent, InvalidStateTransition, ScheduleMode, Status, format_taipei, parse_utc_iso
from ..news import NewsParser, SafeFetcher, UnsafeURLError
from ..scheduler import ScheduleError, Scheduler
from ..storage import Database, DatabaseError, DuplicateError, NotFoundError

PROJECT_ROOT = Path(__file__).resolve().parents[2]
CONFIG_PATH = PROJECT_ROOT / "config" / "default.json"


def load_config() -> dict:
    if CONFIG_PATH.exists():
        return json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
    return {"database": "data/pttposter.db"}


def open_db(args) -> Database:
    path = args.db or load_config().get("database", "data/pttposter.db")
    if not Path(path).is_absolute():
        path = str(PROJECT_ROOT / path)
    db = Database(path)
    db.initialize()
    return db


def parse_article(args):
    parser = NewsParser(fetcher=SafeFetcher())
    if args.fixture:
        html = Path(args.fixture).read_text(encoding=args.encoding)
        return parser.parse_html(html, args.url or f"https://fixture.local/{Path(args.fixture).name}")
    return parser.parse_url(args.url)


def cmd_db_init(args, db):
    print(f"資料庫已初始化: {db.path}")
    return 0


def cmd_news_parse(args, db):
    article = parse_article(args)
    print(json.dumps(article.to_dict(), ensure_ascii=False, indent=2))
    return 0 if article.parse_status in ("OK", "PARTIAL") else 1


def cmd_draft_create(args, db):
    article = parse_article(args)
    if article.parse_status in ("ERROR", "BLOCKED"):
        print(json.dumps(article.to_dict(), ensure_ascii=False, indent=2), file=sys.stderr)
        print(f"無法建立草稿: {article.parse_error}", file=sys.stderr)
        return 1
    sig = None
    if args.signature:
        sig = resolve_signature(db.list_signatures(), name=args.signature)
    else:
        sig = db.get_default_signature()
    content = build_draft(article, signature=sig.content if sig else "")
    formatter = ArticleFormatter()
    try:
        fingerprint = formatter.fingerprint(content)
    except Exception:
        fingerprint = ""
    draft_id = db.create_draft(
        content,
        normalized_url=article.normalized_url,
        fingerprint=fingerprint,
        signature_id=sig.id if sig else None,
    )
    print(draft_id)
    return 0


def cmd_draft_preview(args, db):
    draft = db.get_draft(args.draft_id)
    if draft is None:
        print(f"草稿不存在: {args.draft_id}", file=sys.stderr)
        return 1
    sig = db.get_signature(draft["signature_id"]) if draft["signature_id"] else None
    content = DraftContent(
        title=draft["title"], source=draft["source"], reporter=draft["reporter"],
        body=draft["body"], url=draft["url"], signature=sig.content if sig else "",
    )
    print(ArticleFormatter().preview(content), end="")
    return 0


def cmd_draft_format(args, db):
    draft = db.get_draft(args.draft_id)
    if draft is None:
        print(f"草稿不存在: {args.draft_id}", file=sys.stderr)
        return 1
    sig = db.get_signature(draft["signature_id"]) if draft["signature_id"] else None
    content = DraftContent(
        title=draft["title"], source=draft["source"], reporter=draft["reporter"],
        body=draft["body"], url=draft["url"], signature=sig.content if sig else "",
    )
    formatter = ArticleFormatter()
    text = formatter.format(content)
    fingerprint = formatter.fingerprint(content)
    if args.save:
        db.save_snapshot(args.draft_id, text, fingerprint)
        db.update_draft(args.draft_id, fingerprint=fingerprint)
    print(text, end="")
    print(f"\n---\nfingerprint: {fingerprint}", file=sys.stderr)
    return 0


def cmd_board_add(args, db):
    board = db.add_board(args.name, args.description or "")
    print(f"board_id={board.id}")
    return 0


def cmd_board_list(args, db):
    for board in db.search_boards(args.keyword or ""):
        mark = "*" if board.is_bookmarked else " "
        print(f"{mark} {board.id}\t{board.name}\t{board.description}")
    return 0


def cmd_board_bookmark(args, db):
    print(db.set_board_bookmark(args.board_id, not args.unset))
    return 0


def cmd_board_delete(args, db):
    print(db.delete_board(args.board_id))
    return 0


def cmd_signature_add(args, db):
    sig = db.add_signature(args.name, args.content, is_default=args.default)
    print(f"signature_id={sig.id}")
    return 0


def cmd_signature_list(args, db):
    for sig in db.list_signatures():
        mark = "*" if sig.is_default else " "
        print(f"{mark} {sig.id}\t{sig.name}")
    return 0


def cmd_signature_delete(args, db):
    print(db.delete_signature(args.signature_id))
    return 0


def cmd_signature_default(args, db):
    print(db.set_default_signature(args.signature_id))
    return 0


def cmd_schedule_add(args, db):
    sched = Scheduler(db)
    run_at = None
    if args.at:
        run_at = parse_utc_iso(args.at) if args.at.endswith("Z") or "+" in args.at else args.at
    task_id = sched.schedule(
        args.draft_id, ScheduleMode(args.mode), run_at=run_at,
        board_id=args.board_id, signature_id=args.signature_id,
    )
    print(task_id)
    return 0


def cmd_schedule_list(args, db):
    tasks = db.list_tasks(status=Status(args.status) if args.status else None)
    for task in tasks:
        run_local = format_taipei(parse_utc_iso(task["run_at_utc"]))
        print(f"{task['task_id']}\t{task['status']}\t{task['schedule_mode']}\t{run_local}\tapproved={task['approved']}")
    return 0


def cmd_schedule_pending(args, db):
    for task in Scheduler(db).pending():
        print(f"{task['task_id']}\t{task['status']}\t{task['run_at_utc']}")
    return 0


def cmd_schedule_action(action):
    def handler(args, db):
        sched = Scheduler(db)
        if action == "approve":
            result = sched.approve(args.task_id)
        elif action == "cancel":
            result = sched.cancel(args.task_id)
        elif action == "pause":
            result = sched.pause(args.task_id)
        elif action == "resume":
            result = sched.resume(args.task_id)
        else:  # pragma: no cover
            raise ValueError(action)
        print(result)
        return 0
    return handler


def cmd_schedule_tick(args, db):
    for event in Scheduler(db).tick():
        print(json.dumps(event, ensure_ascii=False))
    return 0


def cmd_recover(args, db):
    recovered = db.recover_incomplete_tasks()
    for task_id in recovered:
        print(task_id)
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="pttposter", description="PTT 新聞自動 PO 文工具（Phase 2A 本機核心）")
    parser.add_argument("--db", help="SQLite 資料庫路徑")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("init", help="初始化資料庫")
    p.set_defaults(func=cmd_db_init)

    p = sub.add_parser("recover", help="恢復未完成的本機任務")
    p.set_defaults(func=cmd_recover)

    news = sub.add_parser("news", help="新聞解析")
    news_sub = news.add_subparsers(dest="news_command", required=True)
    np = news_sub.add_parser("parse", help="解析新聞 URL 或本機 fixture")
    np.add_argument("--url")
    np.add_argument("--fixture")
    np.add_argument("--encoding", default="utf-8")
    np.set_defaults(func=cmd_news_parse)

    draft = sub.add_parser("draft", help="草稿管理")
    draft_sub = draft.add_subparsers(dest="draft_command", required=True)
    dc = draft_sub.add_parser("create")
    dc.add_argument("--url")
    dc.add_argument("--fixture")
    dc.add_argument("--encoding", default="utf-8")
    dc.add_argument("--signature")
    dc.set_defaults(func=cmd_draft_create)
    for name, fn in (("preview", cmd_draft_preview), ("format", cmd_draft_format)):
        dp = draft_sub.add_parser(name)
        dp.add_argument("draft_id")
        if name == "format":
            dp.add_argument("--save", action="store_true")
        dp.set_defaults(func=fn)

    board = sub.add_parser("board", help="看板資料管理")
    board_sub = board.add_subparsers(dest="board_command", required=True)
    ba = board_sub.add_parser("add"); ba.add_argument("name"); ba.add_argument("--description"); ba.set_defaults(func=cmd_board_add)
    bl = board_sub.add_parser("list"); bl.add_argument("--keyword"); bl.set_defaults(func=cmd_board_list)
    bb = board_sub.add_parser("bookmark"); bb.add_argument("board_id", type=int); bb.add_argument("--unset", action="store_true"); bb.set_defaults(func=cmd_board_bookmark)
    bd = board_sub.add_parser("delete"); bd.add_argument("board_id", type=int); bd.set_defaults(func=cmd_board_delete)

    sig = sub.add_parser("signature", help="簽名檔管理")
    sig_sub = sig.add_subparsers(dest="signature_command", required=True)
    sa = sig_sub.add_parser("add"); sa.add_argument("name"); sa.add_argument("content"); sa.add_argument("--default", action="store_true"); sa.set_defaults(func=cmd_signature_add)
    sl = sig_sub.add_parser("list"); sl.set_defaults(func=cmd_signature_list)
    sd = sig_sub.add_parser("default"); sd.add_argument("signature_id", type=int); sd.set_defaults(func=cmd_signature_default)
    sx = sig_sub.add_parser("delete"); sx.add_argument("signature_id", type=int); sx.set_defaults(func=cmd_signature_delete)

    sched = sub.add_parser("schedule", help="排程管理")
    sched_sub = sched.add_subparsers(dest="schedule_command", required=True)
    sc = sched_sub.add_parser("add")
    sc.add_argument("--draft-id", required=True)
    sc.add_argument("--mode", required=True, choices=[m.value for m in ScheduleMode])
    sc.add_argument("--at")
    sc.add_argument("--board-id", type=int)
    sc.add_argument("--signature-id", type=int)
    sc.set_defaults(func=cmd_schedule_add)
    st = sched_sub.add_parser("list"); st.add_argument("--status"); st.set_defaults(func=cmd_schedule_list)
    sp = sched_sub.add_parser("pending"); sp.set_defaults(func=cmd_schedule_pending)
    for action in ("approve", "cancel", "pause", "resume"):
        ap = sched_sub.add_parser(action); ap.add_argument("task_id"); ap.set_defaults(func=cmd_schedule_action(action))
    tk = sched_sub.add_parser("tick"); tk.set_defaults(func=cmd_schedule_tick)

    return parser


def main(argv=None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if not hasattr(args, "db"):
        args.db = None
    db = open_db(args)
    try:
        return args.func(args, db)
    except (DuplicateError, NotFoundError, DatabaseError, ScheduleError,
            InvalidStateTransition, MissingRequiredField, UnsafeURLError) as exc:
        print(f"錯誤: {exc}", file=sys.stderr)
        return 1
    finally:
        db.close()


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
