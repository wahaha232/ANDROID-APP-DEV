from __future__ import annotations

import ipaddress
import socket
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

ALLOWED_SCHEMES = frozenset({"http", "https"})

TRACKING_PARAMS = frozenset({
    "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
    "fbclid", "gclid", "gclsrc", "dclid", "msclkid", "mc_cid", "mc_eid",
    "igshid", "mkt_tok", "yclid", "ref", "ref_src", "spm", "_ga",
})

BLOCKED_HOSTNAMES = frozenset({
    "localhost", "localhost.localdomain", "ip6-localhost", "ip6-loopback",
    "metadata", "metadata.google.internal", "metadata.goog",
})

METADATA_IPS = frozenset({"169.254.169.254", "169.254.170.2", "fd00:ec2::254"})

_EXTRA_BLOCKED_NETWORKS = (
    ipaddress.ip_network("100.64.0.0/10"),
    ipaddress.ip_network("192.0.0.0/24"),
    ipaddress.ip_network("198.18.0.0/15"),
    ipaddress.ip_network("fec0::/10"),
    ipaddress.ip_network("2002::/16"),
    ipaddress.ip_network("2001::/32"),
)


class UnsafeURLError(ValueError):
    pass


class InvalidURLError(ValueError):
    pass


def _ip_is_blocked(value: str) -> bool:
    if value in METADATA_IPS:
        return True
    try:
        obj = ipaddress.ip_address(value)
    except ValueError:
        return False
    if (
        obj.is_private
        or obj.is_loopback
        or obj.is_link_local
        or obj.is_multicast
        or obj.is_reserved
        or obj.is_unspecified
        or getattr(obj, "is_site_local", False)
    ):
        return True
    for net in _EXTRA_BLOCKED_NETWORKS:
        if obj.version == net.version and obj in net:
            return True
    return False


is_blocked_ip = _ip_is_blocked


def _default_resolver(host: str, port: int) -> list[str]:
    infos = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
    return sorted({info[4][0] for info in infos})


def normalize_url(url: str) -> str:
    parts = urlsplit(url.strip())
    scheme = parts.scheme.lower()
    host = (parts.hostname or "").lower()
    if not scheme or not host:
        raise InvalidURLError(f"無效的 URL: {url!r}")
    try:
        port = parts.port
    except ValueError as exc:
        raise InvalidURLError(f"無效的埠號: {url!r}") from exc
    default_port = 80 if scheme == "http" else 443 if scheme == "https" else None
    netloc = host if (port is None or port == default_port) else f"{host}:{port}"
    path = parts.path or "/"
    pairs = [(k, v) for k, v in parse_qsl(parts.query, keep_blank_values=True)
             if k.lower() not in TRACKING_PARAMS]
    query = urlencode(pairs)
    return urlunsplit((scheme, netloc, path, query, ""))


def resolve_public_ip(url: str, resolver=None) -> str:
    parts = urlsplit(url.strip())
    scheme = parts.scheme.lower()
    if scheme not in ALLOWED_SCHEMES:
        raise UnsafeURLError(f"不允許的協定: {scheme or '(空)'}（僅允許 http/https）")
    host = parts.hostname
    if not host:
        raise UnsafeURLError("URL 缺少主機名稱")
    if host.lower() in BLOCKED_HOSTNAMES:
        raise UnsafeURLError(f"封鎖的主機名稱: {host}")
    try:
        port = parts.port or (443 if scheme == "https" else 80)
    except ValueError as exc:
        raise UnsafeURLError(f"無效的埠號: {url!r}") from exc

    try:
        ipaddress.ip_address(host)
        literal = True
    except ValueError:
        literal = False

    if literal:
        if _ip_is_blocked(host):
            raise UnsafeURLError(f"封鎖的 IP 位址: {host}")
        return host

    resolve = resolver or _default_resolver
    try:
        ips = resolve(host, port)
    except OSError as exc:
        raise UnsafeURLError(f"DNS 解析失敗: {host}: {exc}") from exc
    if not ips:
        raise UnsafeURLError(f"無法解析主機: {host}")
    for ip in ips:
        if _ip_is_blocked(ip):
            raise UnsafeURLError(f"主機 {host} 解析到被封鎖的位址 {ip}")
    return ips[0]


def validate_public_url(url: str, resolver=None) -> str:
    resolve_public_ip(url, resolver=resolver)
    return url


def same_normalized(a: str, b: str) -> bool:
    try:
        return normalize_url(a) == normalize_url(b)
    except InvalidURLError:
        return False
