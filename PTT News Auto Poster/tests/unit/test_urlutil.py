from __future__ import annotations

import unittest

from src.news.urlutil import (
    InvalidURLError,
    UnsafeURLError,
    normalize_url,
    same_normalized,
    validate_public_url,
)


def resolver_returning(*ips):
    def _resolve(host, port):
        return list(ips)
    return _resolve


class NormalizeUrlTests(unittest.TestCase):
    def test_lowercases_host_and_scheme_and_strips_fragment(self):
        out = normalize_url("HTTPS://WWW.Example.Test/Path#section")
        self.assertEqual(out, "https://www.example.test/Path")

    def test_removes_default_port(self):
        self.assertEqual(normalize_url("http://example.test:80/a"), "http://example.test/a")
        self.assertEqual(normalize_url("https://example.test:443/a"), "https://example.test/a")

    def test_keeps_non_default_port(self):
        self.assertEqual(normalize_url("https://example.test:8443/a"), "https://example.test:8443/a")

    def test_strips_tracking_params(self):
        out = normalize_url("https://example.test/a?utm_source=x&id=7&fbclid=z&gclid=q")
        self.assertEqual(out, "https://example.test/a?id=7")

    def test_empty_path_becomes_root(self):
        self.assertEqual(normalize_url("https://example.test"), "https://example.test/")

    def test_invalid_url_raises(self):
        with self.assertRaises(InvalidURLError):
            normalize_url("not a url")

    def test_same_normalized(self):
        self.assertTrue(same_normalized(
            "https://example.test/a?utm_source=1",
            "https://example.test/a",
        ))
        self.assertFalse(same_normalized("https://example.test/a", "https://example.test/b"))


class SsrfValidationTests(unittest.TestCase):
    def test_blocks_localhost(self):
        with self.assertRaises(UnsafeURLError):
            validate_public_url("http://localhost/admin")

    def test_blocks_loopback_ip(self):
        with self.assertRaises(UnsafeURLError):
            validate_public_url("http://127.0.0.1/")

    def test_blocks_ipv6_loopback(self):
        with self.assertRaises(UnsafeURLError):
            validate_public_url("http://[::1]/")

    def test_blocks_private_ranges(self):
        for url in ("http://10.0.0.1/", "http://192.168.1.1/", "http://172.16.5.5/"):
            with self.assertRaises(UnsafeURLError):
                validate_public_url(url)

    def test_blocks_cloud_metadata(self):
        with self.assertRaises(UnsafeURLError):
            validate_public_url("http://169.254.169.254/latest/meta-data/")
        with self.assertRaises(UnsafeURLError):
            validate_public_url("http://metadata.google.internal/")

    def test_blocks_hostname_resolving_to_private(self):
        with self.assertRaises(UnsafeURLError):
            validate_public_url("http://evil.test/", resolver=resolver_returning("10.1.2.3"))

    def test_blocks_ipv6_site_local_and_6to4(self):
        for url in ("http://[fec0::1]/", "http://[2002:a9fe:a9fe::]/", "http://[2001:0000::1]/"):
            with self.assertRaises(UnsafeURLError):
                validate_public_url(url)

    def test_blocks_disallowed_scheme(self):
        for url in ("file:///etc/passwd", "ftp://example.test/x", "gopher://example.test/"):
            with self.assertRaises(UnsafeURLError):
                validate_public_url(url)

    def test_allows_public_host(self):
        url = "https://news.example.test/a"
        self.assertEqual(
            validate_public_url(url, resolver=resolver_returning("93.184.216.34")),
            url,
        )

    def test_allows_public_ip_literal(self):
        url = "https://93.184.216.34/a"
        self.assertEqual(validate_public_url(url), url)

    def test_dns_failure_raises_unsafe(self):
        def failing(host, port):
            raise OSError("名稱解析失敗")
        with self.assertRaises(UnsafeURLError):
            validate_public_url("http://no-such.test/", resolver=failing)


if __name__ == "__main__":
    unittest.main()
