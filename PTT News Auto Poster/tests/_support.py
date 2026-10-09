from __future__ import annotations

from pathlib import Path

from src.news.fetcher import FetchResult

FIXTURES = Path(__file__).resolve().parent / "fixtures"


def read_fixture(name: str, encoding: str = "utf-8") -> str:
    return (FIXTURES / name).read_text(encoding=encoding)


def read_fixture_bytes(name: str) -> bytes:
    return (FIXTURES / name).read_bytes()


class FakeFetcher:
    def __init__(self, body=b"", status=200, headers=None, final_url=None,
                 charset="utf-8", exc=None):
        self.body = body
        self.status = status
        self.headers = headers or {"Content-Type": f"text/html; charset={charset}"}
        self.final_url = final_url
        self.charset = charset
        self.exc = exc
        self.calls: list[str] = []

    def fetch(self, url: str) -> FetchResult:
        self.calls.append(url)
        if self.exc is not None:
            raise self.exc
        return FetchResult(
            url=url,
            final_url=self.final_url or url,
            status=self.status,
            headers=self.headers,
            body=self.body,
            charset=self.charset,
        )


class FakeResponse:
    def __init__(self, body: bytes, status=200, headers=None, url="http://public.test/"):
        self._body = body
        self._pos = 0
        self.status = status
        self.headers = headers if headers is not None else {"Content-Type": "text/html"}
        self._url = url

    def read(self, n=-1):
        if n is None or n < 0:
            chunk = self._body[self._pos:]
            self._pos = len(self._body)
            return chunk
        chunk = self._body[self._pos:self._pos + n]
        self._pos += len(chunk)
        return chunk

    def geturl(self):
        return self._url

    def getcode(self):
        return self.status

    def __enter__(self):
        return self

    def __exit__(self, *exc_info):
        return False


class FakeOpener:
    def __init__(self, response=None, exc=None):
        self.response = response
        self.exc = exc
        self.requests = []

    def open(self, request, timeout=None):
        self.requests.append(request)
        if self.exc is not None:
            raise self.exc
        return self.response
