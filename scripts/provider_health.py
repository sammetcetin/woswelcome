#!/usr/bin/env python3
"""CloudStream sağlayıcıları için hafif HTTP/scraping sağlık taraması."""

from __future__ import annotations

import argparse
import concurrent.futures
import dataclasses
import datetime
import html.parser
import ipaddress
import json
import re
import socket
import ssl
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


MAIN_URL_RE = re.compile(
    r'override\s+(?:var|val)\s+mainUrl\s*=\s*"([^"]+)"'
)
STRING_CONSTANT_RE = re.compile(
    r'(?:private\s+)?(?:override\s+)?(?:var|val)\s+(\w+)\s*=\s*"([^"]+)"'
)
URL_LITERAL_RE = re.compile(r'"((?:https?://|\$\{?\w+\}?)[^"\r\n]*)"')
SELECTOR_RE = re.compile(
    r'\.select(?:First)?\(\s*"((?:\\.|[^"\\])*)"\s*\)'
)
CHALLENGE_MARKERS = (
    "cf-chl-",
    "cloudflare ray id",
    "checking your browser",
    "just a moment...",
    "verify you are human",
    "access denied",
)
USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/140.0.0.0 Safari/537.36"
)
MAX_RESPONSE_BYTES = 2 * 1024 * 1024
DNS_BLOCK_IPS = {
    ipaddress.ip_address("195.175.254.2"),
    ipaddress.ip_address("2a01:358:4014:a00::3"),
}


@dataclasses.dataclass(frozen=True)
class Provider:
    module: str
    source: Path
    main_url: str
    probe_url: str
    selectors: tuple[str, ...]
    headers: dict[str, str]


@dataclasses.dataclass
class ProbeResult:
    module: str
    status: str
    http_status: int | None
    requested_url: str
    final_url: str | None
    elapsed_ms: int
    content_type: str | None
    bytes_read: int
    selector_matches: int
    selector_total: int
    signal: str
    note: str

    def as_dict(self) -> dict[str, Any]:
        return dataclasses.asdict(self)


class HtmlInventory(html.parser.HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.tags: set[str] = set()
        self.ids: set[str] = set()
        self.classes: set[str] = set()

    def handle_starttag(
        self, tag: str, attrs: list[tuple[str, str | None]]
    ) -> None:
        self.tags.add(tag.lower())
        for key, value in attrs:
            if not value:
                continue
            if key.lower() == "id":
                self.ids.add(value)
            elif key.lower() == "class":
                self.classes.update(value.split())


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--timeout", type=float, default=15.0)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--json", type=Path)
    parser.add_argument("--markdown", type=Path)
    return parser.parse_args()


def resolve_constants(constants: dict[str, str]) -> dict[str, str]:
    resolved = dict(constants)
    for _ in range(len(resolved) + 1):
        changed = False
        for key, value in tuple(resolved.items()):
            expanded = value
            for name, replacement in resolved.items():
                if name == key:
                    continue
                expanded = expanded.replace(f"${{{name}}}", replacement)
                expanded = expanded.replace(f"${name}", replacement)
            if expanded != value:
                resolved[key] = expanded
                changed = True
        if not changed:
            break
    return resolved


def kotlin_map(code: str, variable: str) -> dict[str, str]:
    marker = re.search(rf"(?:private\s+)?val\s+{re.escape(variable)}\s*=\s*mapOf\(", code)
    if marker is None:
        return {}

    start = marker.end()
    depth = 1
    in_string = False
    escaped = False
    end = start
    for end in range(start, len(code)):
        char = code[end]
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            continue
        if char == '"':
            in_string = True
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                break

    block = code[start:end]
    headers: dict[str, str] = {}
    entry_re = re.compile(
        r'"([^"\\]+)"\s+to\s+(?:\(\s*)?'
        r'((?:"(?:\\.|[^"\\])*"\s*(?:\+\s*)?)+)',
        re.DOTALL,
    )
    for match in entry_re.finditer(block):
        pieces = re.findall(r'"((?:\\.|[^"\\])*)"', match.group(2))
        headers[match.group(1).title()] = "".join(pieces)
    return headers


def discover_providers(repo: Path) -> list[Provider]:
    settings = (repo / "settings.gradle.kts").read_text(encoding="utf-8")
    disabled_match = re.search(r"val\s+disabled\s*=\s*listOf\(([^)]*)\)", settings)
    disabled = (
        set(re.findall(r'"([^"]+)"', disabled_match.group(1)))
        if disabled_match
        else set()
    )

    providers: list[Provider] = []
    for build_file in sorted(repo.glob("*/build.gradle.kts")):
        module = build_file.parent.name
        if module in disabled:
            continue

        source = next(
            iter(build_file.parent.glob(f"src/main/kotlin/**/{module}.kt")),
            None,
        )
        if source is None:
            continue

        code = source.read_text(encoding="utf-8")
        constants = resolve_constants(dict(STRING_CONSTANT_RE.findall(code)))
        main_url_match = MAIN_URL_RE.search(code)
        main_url = main_url_match.group(1).rstrip("/") if main_url_match else ""
        main_page_section = code
        if "override val mainPage" in code:
            main_page_section = code.split("override val mainPage", 1)[1]
            main_page_section = main_page_section.split(
                "override suspend fun getMainPage", 1
            )[0]

        candidates: list[str] = []
        first_page = (
            "0"
            if re.search(r"page\s*=\s*page\s*-\s*1", code)
            else "1"
        )
        for match in URL_LITERAL_RE.finditer(main_page_section):
            candidate = match.group(1)
            for name, value in constants.items():
                candidate = candidate.replace(f"${{{name}}}", value)
                candidate = candidate.replace(f"${name}", value)
            candidate = candidate.replace("SAYFA", first_page)
            if candidate.endswith("page/"):
                candidate += first_page
            elif candidate.endswith("page="):
                candidate += first_page
            elif candidate.endswith("/page"):
                candidate += f"/{first_page}"
            if "$" not in candidate and candidate.startswith(("http://", "https://")):
                candidates.append(candidate)

        selectors = tuple(dict.fromkeys(SELECTOR_RE.findall(code)))
        if not main_url and candidates:
            parsed_candidate = urllib.parse.urlparse(candidates[0])
            main_url = f"{parsed_candidate.scheme}://{parsed_candidate.netloc}"
        if not main_url:
            continue

        probe_url = constants.get("healthProbeUrl") or (
            candidates[0] if candidates else main_url
        )
        headers = kotlin_map(code, "apiHeaders")
        providers.append(
            Provider(module, source, main_url, probe_url, selectors, headers)
        )

    return providers


def selector_matches(body: str, selectors: tuple[str, ...]) -> int:
    if not selectors or "<" not in body:
        return 0

    inventory = HtmlInventory()
    try:
        inventory.feed(body)
    except Exception:
        return 0

    matches = 0
    for selector in selectors:
        classes = set(re.findall(r"\.([A-Za-z_][\w-]*)", selector))
        ids = set(re.findall(r"#([A-Za-z_][\w-]*)", selector))
        tags = set(
            match.group(1).lower()
            for match in re.finditer(
                r"(?:^|[\s>+~,])([A-Za-z][\w-]*)", selector
            )
        )
        markers = bool(classes or ids or tags)
        if markers and (
            classes.issubset(inventory.classes)
            and ids.issubset(inventory.ids)
            and tags.issubset(inventory.tags)
        ):
            matches += 1
    return matches


def content_signal(content_type: str, body: bytes) -> tuple[str, str]:
    text = body.decode("utf-8", errors="replace")
    lowered = text[:200_000].lower()

    if any(marker in lowered for marker in CHALLENGE_MARKERS):
        return "challenge", "Bot koruması veya erişim engeli algılandı"
    if text.lstrip().startswith("#EXTM3U"):
        return "m3u", "Geçerli M3U başlangıcı bulundu"
    if "json" in content_type or text.lstrip().startswith(("{", "[")):
        try:
            json.loads(text)
            return "json", "JSON gövdesi ayrıştırıldı"
        except json.JSONDecodeError:
            return "invalid-json", "JSON benzeri gövde ayrıştırılamadı"
    if "html" in content_type or "<html" in lowered or "<!doctype" in lowered:
        return "html", "HTML gövdesi alındı"
    if body:
        return "content", "Boş olmayan gövde alındı"
    return "empty", "Yanıt gövdesi boş"


def same_origin(first: str, second: str) -> bool:
    first_url = urllib.parse.urlparse(first)
    second_url = urllib.parse.urlparse(second)
    return (
        first_url.scheme.lower(),
        (first_url.hostname or "").lower().removeprefix("www."),
    ) == (
        second_url.scheme.lower(),
        (second_url.hostname or "").lower().removeprefix("www."),
    )


def dns_block_addresses(url: str) -> list[str]:
    hostname = urllib.parse.urlparse(url).hostname
    if not hostname:
        return []
    try:
        addresses = {
            ipaddress.ip_address(item[4][0])
            for item in socket.getaddrinfo(hostname, 443)
        }
    except (OSError, ValueError):
        return []

    blocked = [
        str(address)
        for address in addresses
        if address in DNS_BLOCK_IPS or address.is_loopback
    ]
    return sorted(blocked)


def curl_fallback(
    provider: Provider, timeout: float
) -> tuple[bytes, int, str, str | None] | None:
    """Retry requests rejected by TLS/client-fingerprint protection with curl."""
    with tempfile.TemporaryDirectory(prefix="provider-health-") as temp_dir:
        body_path = Path(temp_dir) / "body"
        command = [
            "curl",
            "--location",
            "--silent",
            "--show-error",
            "--compressed",
            "--max-time",
            str(max(1, round(timeout))),
            "--max-filesize",
            str(MAX_RESPONSE_BYTES),
            "--output",
            str(body_path),
            "--write-out",
            "%{http_code}\n%{url_effective}\n%{content_type}",
        ]
        for key, value in provider.headers.items():
            command.extend(("--header", f"{key}: {value}"))
        command.append(provider.probe_url)

        try:
            completed = subprocess.run(
                command,
                capture_output=True,
                text=True,
                timeout=timeout + 5,
                check=False,
            )
        except (FileNotFoundError, subprocess.TimeoutExpired, OSError):
            return None

        # curl uses 63 when --max-filesize intentionally stops a large body.
        if completed.returncode not in {0, 63} or not body_path.exists():
            return None
        metadata = completed.stdout.splitlines()
        if len(metadata) < 2 or not metadata[0].isdigit():
            return None
        http_status = int(metadata[0])
        if http_status == 0:
            return None
        body = body_path.read_bytes()[:MAX_RESPONSE_BYTES]
        content_type = metadata[2].split(";", 1)[0] if len(metadata) > 2 else None
        return body, http_status, metadata[1], content_type or None


def probe(provider: Provider, timeout: float) -> ProbeResult:
    started = time.monotonic()
    # Performans kısayolu: yerel DNS engeli bilinen hostlar için HTTP
    # zaman aşımını beklemeden doğrudan blocked dön. Bu yalnız getaddrinfo
    # sonucuna bakar; engeli DoH vb. ile aşmaya çalışmaz, dolayısıyla
    # sağlık sonucunu değiştirmez, sadece yavaş timeout'ları kısaltır.
    pre_blocked = dns_block_addresses(provider.probe_url)
    if pre_blocked:
        return ProbeResult(
            provider.module,
            "blocked",
            None,
            provider.probe_url,
            None,
            round((time.monotonic() - started) * 1000),
            None,
            0,
            0,
            len(provider.selectors),
            "dns-block",
            "Alan adı yerel DNS engelleme adresine çözümlendi: "
            + ", ".join(pre_blocked),
        )
    request_headers = {
        "User-Agent": USER_AGENT,
        "Accept": "text/html,application/json,application/xhtml+xml,*/*;q=0.8",
        "Accept-Language": "tr-TR,tr;q=0.9,en;q=0.7",
    }
    request_headers.update(provider.headers)
    request = urllib.request.Request(
        provider.probe_url,
        headers=request_headers,
    )

    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read(MAX_RESPONSE_BYTES)
            http_status = response.status
            final_url = response.geturl()
            response_type = response.headers.get_content_type()
    except urllib.error.HTTPError as error:
        body = error.read(MAX_RESPONSE_BYTES)
        http_status = error.code
        final_url = error.geturl()
        response_type = error.headers.get_content_type() if error.headers else None
    except (
        urllib.error.URLError,
        TimeoutError,
        socket.timeout,
        ssl.SSLError,
        OSError,
    ) as error:
        blocked_addresses = dns_block_addresses(provider.probe_url)
        if blocked_addresses:
            return ProbeResult(
                provider.module,
                "blocked",
                None,
                provider.probe_url,
                None,
                round((time.monotonic() - started) * 1000),
                None,
                0,
                0,
                len(provider.selectors),
                "dns-block",
                "Alan adı yerel DNS engelleme adresine çözümlendi: "
                + ", ".join(blocked_addresses),
            )
        fallback = curl_fallback(provider, timeout)
        if fallback is not None:
            body, http_status, final_url, response_type = fallback
        else:
            return ProbeResult(
                provider.module,
                "down",
                None,
                provider.probe_url,
                None,
                round((time.monotonic() - started) * 1000),
                None,
                0,
                0,
                len(provider.selectors),
                "network-error",
                str(getattr(error, "reason", error)),
            )

    elapsed_ms = round((time.monotonic() - started) * 1000)
    decoded = body.decode("utf-8", errors="replace")
    matches = selector_matches(decoded, provider.selectors)
    signal, note = content_signal(response_type or "", body)
    redirected = final_url is not None and not same_origin(provider.probe_url, final_url)

    if http_status >= 500 or http_status in {404, 410}:
        status = "down"
    elif http_status in {401, 403, 429, 451} or signal == "challenge":
        status = "blocked"
    elif not 200 <= http_status < 400:
        status = "degraded"
    elif signal in {"empty", "invalid-json"}:
        status = "degraded"
    elif signal == "html" and provider.selectors and matches == 0:
        status = "degraded"
        note += "; kaynak koddaki seçicilerden hiçbiri ana yanıtta bulunamadı"
    elif redirected:
        status = "redirected"
        note += "; farklı origin'e yönlendirildi"
    else:
        status = "healthy"

    return ProbeResult(
        provider.module,
        status,
        http_status,
        provider.probe_url,
        final_url,
        elapsed_ms,
        response_type,
        len(body),
        matches,
        len(provider.selectors),
        signal,
        note,
    )


def markdown_report(results: list[ProbeResult]) -> str:
    counts: dict[str, int] = {}
    for result in results:
        counts[result.status] = counts.get(result.status, 0) + 1

    summary = ", ".join(
        f"{status}: {count}" for status, count in sorted(counts.items())
    )
    lines = [
        "# Sağlayıcı sağlık raporu",
        "",
        f"Üretim zamanı: {datetime.datetime.now(datetime.UTC).isoformat(timespec='seconds')}",
        "",
        f"Toplam {len(results)} sağlayıcı — {summary}",
        "",
        "| Sağlayıcı | Hedef | Sonuç | HTTP | Sinyal | Seçici | Süre | Not |",
        "|---|---|---:|---:|---|---:|---:|---|",
    ]
    for result in results:
        note = result.note.replace("|", "\\|").replace("\n", " ")
        target = urllib.parse.urlparse(result.requested_url).netloc
        lines.append(
            f"| {result.module} | {target} | {result.status} | "
            f"{result.http_status or '-'} | {result.signal} | "
            f"{result.selector_matches}/{result.selector_total} | "
            f"{result.elapsed_ms} ms | {note} |"
        )
    return "\n".join(lines) + "\n"


def main() -> int:
    args = parse_args()
    repo = args.repo.resolve()
    providers = discover_providers(repo)

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = [pool.submit(probe, provider, args.timeout) for provider in providers]
        results = sorted((future.result() for future in futures), key=lambda item: item.module)

    report = markdown_report(results)
    print(report, end="")

    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(
            json.dumps([result.as_dict() for result in results], ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
    if args.markdown:
        args.markdown.parent.mkdir(parents=True, exist_ok=True)
        args.markdown.write_text(report, encoding="utf-8")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
