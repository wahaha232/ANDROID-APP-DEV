from __future__ import annotations

import functools
import http.client
import re
import socket
from dataclasses import dataclass, field
from typing import Optional, Protocol
from urllib.error import HTTPError, URLError
from urllib.request import (
    HTTPHandler,
    HTTPRedirectHandler,
    HTTPSHandler,
    Request,
    build_opener,
)

from .urlutil import UnsafeURLError, is_blocked_ip, resolve_public_ip, validate_public_url

DEFAULT_USER_AGENT = (
    "Mozilla/5.0 (compatible; PTTNewsBot/0.1; +local-test) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36"
)
DEFAULT_MAX_BYTES = 2_000_000
DEFAULT_TIMEOUT = 10.0
DEFAULT_MAX_REDIRECTS = 5
MAX_REDIRECTS_LIMIT = 10

_CHARSET_RE = re.compile(r"charset\s*=\s*[\"']?([\w\-]+)", re.IGNORECASE)
_META_CHARSET_RE = re.compile(rb"<meta[^>]+charset\s*=\s*[\"']?([\w\-]+)", re.IGNORECASE)


class FetchError(Exception):
    pass


class NetworkError(FetchError):
    pass


class TimeoutError(FetchError):
    pass


class ResponseTooLarge(FetchError):
    pass


class BlockedContent(FetchError):
    pass


class HttpStatusError(FetchError):
    def __init__(self, status: int):
        super().__init__(f"HTTP 狀態 {status}")
        self.status = status


@dataclass
class FetchResult:
    url: str
    final_url: str
    status: int
    headers: dict[str, str] = field(default_factory=dict)
    body: bytes = b""
    charset: str = "utf-8"


class Fetcher(Protocol):
    def fetch(self, url: str) -> FetchResult:  # pragma: no cover - protocol
        ...


def detect_charset(headers: dict[str, str], body: bytes) -> str:
    ctype = ""
    for key, value in headers.items():
        if key.lower() == "content-type":
            ctype = value
            break
    match = _CHARSET_RE.search(ctype)
    if match:
        return match.group(1).lower()
    if body[:3] == b"\xef\xbb\xbf":
        return "utf-8-sig"
    if body[:2] in (b"\xff\xfe", b"\xfe\xff"):
        return "utf-16"
    meta = _META_CHARSET_RE.search(body[:2048])
    if meta:
        return meta.group(1).decode("ascii", "ignore").lower()
    for candidate in ("utf-8", "big5", "cp950"):
        try:
            body.decode(candidate)
            return candidate
        except UnicodeDecodeError:
            continue
    return "latin-1"


class _ValidatingRedirectHandler(HTTPRedirectHandler):
    handler_order = 100

    def __init__(self, validator, max_redirects: int):
        super().__init__()
        self._validator = validator
        self.max_redirections = max_redirects

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        self._validator(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def _verify_peer(sock) -> None:
    try:
        peer = sock.getpeername()[0]
    except OSError:
        return
    if peer and is_blocked_ip(peer):
        raise UnsafeURLError(f"連線對端為被封鎖的位址: {peer}")


class _PinnedHTTPConnection(http.client.HTTPConnection):
    def __init__(self, *args, pinned_ip: str, **kwargs):
        super().__init__(*args, **kwargs)
        self._pinned_ip = pinned_ip

    def connect(self) -> None:
        self.sock = socket.create_connection(
            (self._pinned_ip, self.port), self.timeout, self.source_address)
        _verify_peer(self.sock)


class _PinnedHTTPSConnection(http.client.HTTPSConnection):
    def __init__(self, *args, pinned_ip: str, **kwargs):
        super().__init__(*args, **kwargs)
        self._pinned_ip = pinned_ip

    def connect(self) -> None:
        sock = socket.create_connection(
            (self._pinned_ip, self.port), self.timeout, self.source_address)
        try:
            _verify_peer(sock)
            self.sock = self._context.wrap_socket(sock, server_hostname=self.host)
        except Exception:
            sock.close()
            raise


class _PinnedHTTPHandler(HTTPHandler):
    handler_order = 100

    def __init__(self, resolver=None):
        super().__init__()
        self._resolver = resolver

    def http_open(self, req):
        ip = resolve_public_ip(req.full_url, resolver=self._resolver)
        return self.do_open(functools.partial(_PinnedHTTPConnection, pinned_ip=ip), req)


class _PinnedHTTPSHandler(HTTPSHandler):
    handler_order = 100

    def __init__(self, resolver=None):
        super().__init__()
        self._resolver = resolver

    def https_open(self, req):
        ip = resolve_public_ip(req.full_url, resolver=self._resolver)
        return self.do_open(
            functools.partial(_PinnedHTTPSConnection, pinned_ip=ip), req,
            context=self._context, check_hostname=self._check_hostname,
        )


class SafeFetcher:
    def __init__(
        self,
        timeout: float = DEFAULT_TIMEOUT,
        max_bytes: int = DEFAULT_MAX_BYTES,
        max_redirects: int = DEFAULT_MAX_REDIRECTS,
        resolver=None,
        opener=None,
        user_agent: str = DEFAULT_USER_AGENT,
    ):
        if max_redirects < 0 or max_redirects > MAX_REDIRECTS_LIMIT:
            raise ValueError(f"max_redirects 必須介於 0 與 {MAX_REDIRECTS_LIMIT}")
        self.timeout = timeout
        self.max_bytes = max_bytes
        self.max_redirects = max_redirects
        self.user_agent = user_agent
        self._resolver = resolver
        self._opener = opener

    def _validate(self, url: str) -> str:
        return validate_public_url(url, resolver=self._resolver)

    def _build_opener(self):
        return build_opener(
            _ValidatingRedirectHandler(self._validate, self.max_redirects),
            _PinnedHTTPHandler(self._resolver),
            _PinnedHTTPSHandler(self._resolver),
        )

    def fetch(self, url: str) -> FetchResult:
        self._validate(url)
        opener = self._opener or self._build_opener()
        request = Request(url, headers={
            "User-Agent": self.user_agent,
            "Accept": "text/html,application/xhtml+xml,application/ld+json;q=0.9,*/*;q=0.1",
            "Accept-Language": "zh-TW,zh;q=0.9,en;q=0.5",
        })
        try:
            response = opener.open(request, timeout=self.timeout)
        except HTTPError as exc:
            if exc.code in (401, 402, 403, 407, 429):
                raise BlockedContent(f"HTTP {exc.code} 存取被拒（可能為付費牆、登入或封鎖）") from exc
            raise HttpStatusError(exc.code) from exc
        except URLError as exc:
            reason = exc.reason
            if isinstance(reason, socket.timeout) or "timed out" in str(reason).lower():
                raise TimeoutError(f"連線逾時: {url}") from exc
            raise NetworkError(f"網路錯誤: {reason}") from exc
        except (socket.timeout, TimeoutError) as exc:
            raise TimeoutError(f"連線逾時: {url}") from exc
        except OSError as exc:
            raise NetworkError(f"網路錯誤: {exc}") from exc

        with response:
            status = getattr(response, "status", None) or response.getcode()
            raw_headers = getattr(response, "headers", None)
            headers = {k: v for k, v in raw_headers.items()} if raw_headers else {}
            try:
                body = response.read(self.max_bytes + 1)
            except (socket.timeout, TimeoutError) as exc:
                raise TimeoutError(f"讀取逾時: {url}") from exc
            except OSError as exc:
                raise NetworkError(f"讀取失敗: {exc}") from exc
            final_url = response.geturl()

        if len(body) > self.max_bytes:
            raise ResponseTooLarge(
                f"回應超過上限 {self.max_bytes} bytes（已中止讀取）"
            )
        charset = detect_charset(headers, body)
        return FetchResult(
            url=url, final_url=final_url, status=status,
            headers=headers, body=body, charset=charset,
        )


def fetch_text(result: FetchResult) -> str:
    return result.body.decode(result.charset or "utf-8", errors="replace")
