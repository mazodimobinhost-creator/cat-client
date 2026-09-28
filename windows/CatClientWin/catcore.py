"""Cat Client for Windows — core logic (stdlib only).

Subscriptions, clean-IP scanning, config building and link testing shared by
the tkinter UI. Nothing here touches the UI, so it stays testable headless.
"""
from __future__ import annotations

import base64
import ipaddress
import re
import socket
import ssl
import time
import urllib.request
from urllib.parse import quote

from colo import location_for_colo

VERSION = "1.9.0"
USER_AGENT = f"CatClient-Windows/{VERSION}"

MAX_SOURCE_BYTES = 2_000_000

RECOMMENDED_SNIS = [
    "skk.moe",
    "www.speedtest.net",
    "cdnjs.cloudflare.com",
    "speed.cloudflare.com",
    "www.visa.com",
]

DEFAULT_RANGES = [
    "104.16.0.0/13", "104.24.0.0/14", "172.64.0.0/13", "162.158.0.0/15",
    "188.114.96.0/20", "141.101.64.0/18", "108.162.192.0/18", "103.21.244.0/22",
]

# Public free-config sources (owner, repo, branch, path, display name).
FREE_SOURCES = [
    ("morpheusadam", "v2ray-config", "main", "subs/bundles/mini.txt", "Morpheus measured set"),
    ("morpheusadam", "v2ray-config", "main", "subs/bundles/iran.txt", "Morpheus Iran bundle"),
    ("0xRadikal", "Free-v2ray-Configs", "main", "protocols/vless.txt", "0xRadikal VLESS"),
    ("Epodonios", "v2ray-configs", "main", "All_Configs_Sub.txt", "Epodonios collector"),
    ("aliilapro", "v2rayng-config", "main", "subscription/mix", "ALIILAPRO mix"),
]

_LINK_RE = re.compile(r"^(vless|trojan|ss|vmess|hysteria2|hy2|tuic)://\S+$", re.I)


def load_library(path: str = "ips.txt") -> list[str]:
    """Candidate clean-IP library shipped next to the app."""
    try:
        with open(path, encoding="utf-8") as handle:
            return [line.strip() for line in handle if line.strip() and not line.startswith("#")]
    except OSError:
        return []


def http_get(url: str, max_bytes: int = MAX_SOURCE_BYTES, timeout: int = 12) -> str:
    """HTTPS-only GET with a hard byte cap."""
    if not url.lower().startswith("https://"):
        raise ValueError("only https:// URLs are allowed")
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=timeout) as resp:  # noqa: S310 (user-driven URLs)
        data = resp.read(max_bytes + 1)
    if len(data) > max_bytes:
        raise ValueError("response is too large")
    return data.decode("utf-8", "replace")


def fetch_free_source(source) -> list[str]:
    """Fetch one free-config source through the jsDelivr/ghproxy mirror race."""
    owner, repo, branch, path, name = source
    raw = f"https://raw.githubusercontent.com/{owner}/{repo}/{branch}/{path}"
    mirrors = [
        f"https://cdn.jsdelivr.net/gh/{owner}/{repo}@{branch}/{path}",
        f"https://ghproxy.net/{raw}",
        f"https://raw.gitmirror.com/{owner}/{repo}/{branch}/{path}",
    ]
    last = None
    for url in mirrors:
        try:
            body = http_get(url)
        except Exception as error:  # noqa: BLE001 — try the next mirror
            last = error
            continue
        links = parse_subscription(body)
        if links:
            return [f"{link}\t# {name}" if "\t" not in link else link for link in links]
    if last:
        raise last
    return []


def parse_subscription(text: str) -> list[str]:
    """Raw or base64 subscription body -> individual config links."""
    body = text.strip()
    lines = [line.strip() for line in body.splitlines() if line.strip()]
    if not any(_LINK_RE.match(line) for line in lines):
        try:
            decoded = base64.b64decode(body + "=" * (-len(body) % 4)).decode("utf-8", "replace")
            lines = [line.strip() for line in decoded.splitlines() if line.strip()]
        except Exception:  # noqa: BLE001
            pass
    return [line for line in lines if _LINK_RE.match(line)]


def link_name(link: str) -> str:
    if "#" in link:
        from urllib.parse import unquote

        return unquote(link.rsplit("#", 1)[1])[:64]
    return link.split("://", 1)[0].upper()


def link_host(link: str) -> str:
    try:
        rest = link.split("://", 1)[1]
        hostport = rest.split("@", 1)[1].split("?", 1)[0].split("/", 1)[0].split("#", 1)[0]
        return hostport.rsplit(":", 1)[0].strip("[]")
    except IndexError:
        return ""


def flag_of(iso: str) -> str:
    return "".join(chr(0x1F1E6 + (ord(c) - 65)) for c in iso.upper())


def build_cat_configs(
    host: str,
    uuid: str,
    clean_ips: list[str],
    port: int = 443,
    sni: str = "",
    colos: dict | None = None,
) -> list[str]:
    """VLESS + Trojan links with Cat labels: Cat · location · protocol · port · flag."""
    sni = sni or host
    colos = colos or {}
    out = []
    index = 1
    for ip in clean_ips:
        loc = location_for_colo(colos.get(ip, ""))
        label = f"🐱 Cat · {loc['country']} · {{proto}} · {port} · {loc['flag']} · #{index}"
        vless = (
            f"vless://{uuid}@{ip}:{port}?encryption=none&security=tls&sni={sni}"
            f"&type=ws&path=%2Fws%3Fed%3D2048&fp=chrome&host={host}"
        )
        trojan = f"trojan://{uuid}@{ip}:{port}?security=tls&sni={sni}&type=ws&path=%2Ftrojan%3Fed%3D2048&fp=chrome&host={host}"
        out.append(vless + "#" + quote(label.format(proto="VLESS"), safe=""))
        out.append(trojan + "#" + quote(label.format(proto="Trojan"), safe=""))
        index += 1
    return out


def probe_ip(
    ip: str,
    port: int = 443,
    sni: str = "www.speedtest.net",
    timeout: float = 2.5,
) -> dict | None:
    """Two-stage probe: TCP connect, then TLS + /cdn-cgi/trace for the colo."""
    start = time.perf_counter()
    try:
        with socket.create_connection((ip, port), timeout=timeout):
            pass
    except OSError:
        return None
    ping_ms = int((time.perf_counter() - start) * 1000)

    colo = ""
    tls_ok = False
    tls_ms = None
    ctx = ssl.create_default_context()
    try:
        tls_start = time.perf_counter()
        raw = socket.create_connection((ip, port), timeout=timeout)
        with ctx.wrap_socket(raw, server_hostname=sni) as tls:
            tls_ms = int((time.perf_counter() - tls_start) * 1000)
            tls_ok = True
            tls.sendall(
                b"GET /cdn-cgi/trace HTTP/1.1\r\nHost: " + sni.encode()
                + b"\r\nUser-Agent: " + USER_AGENT.encode() + b"\r\nConnection: close\r\n\r\n"
            )
            data = b""
            while b"\n\n" not in data and len(data) < 4096:
                chunk = tls.recv(1024)
                if not chunk:
                    break
                data += chunk
            for line in data.decode("utf-8", "replace").splitlines():
                if line.startswith("colo="):
                    colo = line[5:].strip()
    except (OSError, ssl.SSLError):
        pass

    loc = location_for_colo(colo)
    return {
        "ip": ip,
        "port": port,
        "ping_ms": ping_ms,
        "tls_ok": tls_ok,
        "tls_ms": tls_ms,
        "colo": colo,
        "country": loc["country"],
        "flag": loc["flag"],
    }


def test_link(link: str, timeout: float = 3.0) -> dict | None:
    """Delay-test a config link by connecting to its server."""
    host = link_host(link)
    if not host or not re.match(r"^\d{1,3}(\.\d{1,3}){3}$", host):
        # Resolve domains so links from subscriptions can be tested too.
        try:
            host = socket.gethostbyname(host or "")
        except OSError:
            return None
    try:
        port_match = re.search(r":(\d+)", link.split("@", 1)[-1])
        port = int(port_match.group(1)) if port_match else 443
    except (IndexError, ValueError):
        port = 443
    return probe_ip(host, port=port, timeout=timeout)


def random_sample(cidr: str, count: int) -> list[str]:
    """`count` random hosts from a CIDR (never .0 / .255)."""
    import random

    try:
        net = ipaddress.ip_network(cidr, strict=False)
    except ValueError:
        return []
    total = net.num_addresses - 2
    if total <= 0:
        return [str(net.network_address + 1)]
    picks = set()
    for _ in range(min(count, total)):
        offset = random.randint(1, total)
        addr = net.network_address + offset
        if str(addr).endswith(".0") or str(addr).endswith(".255"):
            continue
        picks.add(str(addr))
    return sorted(picks)


def build_candidates(ranges_text: str, use_library: bool, library: list[str], per_range: int = 12) -> list[str]:
    ips: list[str] = []
    for part in [p.strip() for p in ranges_text.split(",") if p.strip()]:
        if "/" in part:
            ips.extend(random_sample(part, per_range))
        elif re.match(r"^\d{1,3}(\.\d{1,3}){3}$", part):
            ips.append(part)
    if use_library:
        import random

        pool = list(dict.fromkeys(library))
        random.shuffle(pool)
        ips.extend(pool[:200])
    return list(dict.fromkeys(ips))
