from __future__ import annotations

import socket
import unittest
from types import SimpleNamespace
from urllib.error import HTTPError, URLError

from src.news.fetcher import (
    BlockedContent,
    NetworkError,
    ResponseTooLarge,
    SafeFetcher,
    TimeoutError as FetchTimeoutError,
    _ValidatingRedirectHandler,
    _verify_peer,
    detect_charset,
    fetch_text,
)
from src.news.urlutil import UnsafeURLError, resolve_public_ip, validate_public_url
from tests._support import FakeOpener, FakeResponse

PUBLIC_RESOLVER = lambda host, port: ["93.184.216.34"]


class PinnedResolutionTests(unittest.TestCase):
    def test_resolve_public_ip_pins_vetted_address(self):
        self.assertEqual(
            resolve_public_ip("https://news.test/a", resolver=PUBLIC_RESOLVER),
            "93.184.216.34",
        )

    def test_resolve_public_ip_blocks_private_resolution(self):
        with self.assertRaises(UnsafeURLError):
            resolve_public_ip("https://evil.test/", resolver=lambda h, p: ["10.0.0.5"])

    def test_verify_peer_blocks_internal_address(self):
        class _Sock:
            def getpeername(self):
                return ("127.0.0.1", 443)
        with self.assertRaises(UnsafeURLError):
            _verify_peer(_Sock())

    def test_verify_peer_allows_public_address(self):
        class _Sock:
            def getpeername(self):
                return ("93.184.216.34", 443)
        _verify_peer(_Sock())


class DetectCharsetTests(unittest.TestCase):
    def test_from_header(self):
        self.assertEqual(detect_charset({"Content-Type": "text/html; charset=big5"}, b"abc"), "big5")

    def test_from_meta(self):
        body = b'<html><head><meta charset="Big5"></head></html>'
        self.assertEqual(detect_charset({}, body), "big5")

    def test_from_bom(self):
        self.assertEqual(detect_charset({}, b"\xef\xbb\xbfhello"), "utf-8-sig")

    def test_defaults_to_utf8_when_clean(self):
        self.assertEqual(detect_charset({}, "測試".encode("utf-8")), "utf-8")

    def test_falls_back_to_big5(self):
        self.assertEqual(detect_charset({}, "測試".encode("big5")), "big5")


class SafeFetcherTests(unittest.TestCase):
    def make(self, opener=None, **kwargs):
        return SafeFetcher(resolver=PUBLIC_RESOLVER, opener=opener, **kwargs)

    def test_successful_fetch(self):
        resp = FakeResponse(b"<html>ok</html>", status=200,
                            headers={"Content-Type": "text/html; charset=utf-8"},
                            url="https://www.example.test/final")
        result = self.make(FakeOpener(response=resp)).fetch("https://www.example.test/a")
        self.assertEqual(result.status, 200)
        self.assertEqual(result.final_url, "https://www.example.test/final")
        self.assertEqual(result.charset, "utf-8")
        self.assertEqual(fetch_text(result), "<html>ok</html>")

    def test_blocks_private_target_before_request(self):
        opener = FakeOpener(response=FakeResponse(b"x"))
        with self.assertRaises(UnsafeURLError):
            self.make(opener).fetch("http://127.0.0.1/")
        self.assertEqual(opener.requests, [])

    def test_response_too_large(self):
        resp = FakeResponse(b"a" * 5000)
        with self.assertRaises(ResponseTooLarge):
            self.make(FakeOpener(response=resp), max_bytes=1000).fetch("https://www.example.test/a")

    def test_timeout_classified(self):
        opener = FakeOpener(exc=URLError(socket.timeout("timed out")))
        with self.assertRaises(FetchTimeoutError):
            self.make(opener).fetch("https://www.example.test/a")

    def test_network_error_classified(self):
        opener = FakeOpener(exc=URLError("connection refused"))
        with self.assertRaises(NetworkError):
            self.make(opener).fetch("https://www.example.test/a")

    def test_http_403_is_blocked(self):
        err = HTTPError("https://www.example.test/a", 403, "Forbidden", {}, None)
        with self.assertRaises(BlockedContent):
            self.make(FakeOpener(exc=err)).fetch("https://www.example.test/a")

    def test_invalid_max_redirects(self):
        with self.assertRaises(ValueError):
            SafeFetcher(max_redirects=99)


class RedirectSsrfTests(unittest.TestCase):
    def test_redirect_to_private_ip_blocked(self):
        handler = _ValidatingRedirectHandler(
            lambda url: validate_public_url(url, resolver=PUBLIC_RESOLVER), 5)
        req = SimpleNamespace(full_url="https://public.test/a", headers={})
        with self.assertRaises(UnsafeURLError):
            handler.redirect_request(req, None, 302, "Found", {}, "http://10.0.0.1/")

    def test_redirect_to_metadata_blocked(self):
        handler = _ValidatingRedirectHandler(
            lambda url: validate_public_url(url, resolver=PUBLIC_RESOLVER), 5)
        req = SimpleNamespace(full_url="https://public.test/a", headers={})
        with self.assertRaises(UnsafeURLError):
            handler.redirect_request(req, None, 302, "Found", {},
                                     "http://169.254.169.254/latest/meta-data/")


if __name__ == "__main__":
    unittest.main()
