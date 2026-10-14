#!/usr/bin/env python3
"""Build the offline IP -> country table packaged with the app.

Source: DB-IP "IP to Country Lite" (CC BY 4.0, https://db-ip.com), a monthly CSV
`https://download.db-ip.com/free/dbip-country-lite-YYYY-MM.csv.gz` with rows
`first_ip,last_ip,CC`. The app uses it only to put a flag on a server row from the
server's own address; it never leaves the device and is refreshed only together with
the APK (no runtime download).

Output (stdlib only, deterministic for a given input):
  app/src/main/assets/geoip/country.bin     compact binary table, format below
  app/src/main/assets/geoip/manifest.json   source URL, month, sizes, SHA-256 of both files

Usage:
  scripts/build-geoip-country.py                 # current month, falls back to the previous one
  scripts/build-geoip-country.py --month 2026-10
  scripts/build-geoip-country.py --input dbip-country-lite-2026-10.csv.gz --month 2026-10
  scripts/build-geoip-country.py --check         # verify committed asset against manifest only

country.bin, all integers big-endian:
  4   magic "ZGC1"
  4   u32 month, YYYYMM
  1   u8 country count N (1..255)
  2*N country codes, ASCII; table index 1..N (index 0 is never written)
  4   u32 IPv4 entry count      4   u32 IPv4 stream byte length
  4   u32 IPv6 entry count      4   u32 IPv6 stream byte length
  IPv4 stream, then IPv6 stream. Each entry: varint(start - previous start), u8 country index.
  An entry covers [start, next start). IPv4 start is the full 32-bit address; IPv6 start is
  the upper 48 bits (a /48), which is finer than any allocation that matters for a server.
  Varint: 7 bits per byte, least significant group first, high bit = continuation.
Unknown ("ZZ") ranges are folded into the preceding country: a lookup of reserved or
private space is rejected by the app before it reaches the table.
"""

from __future__ import annotations

import argparse
import csv
import datetime
import gzip
import hashlib
import ipaddress
import json
from pathlib import Path
import struct
import sys
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets/geoip"
BIN = ASSETS / "country.bin"
MANIFEST = ASSETS / "manifest.json"
URL = "https://download.db-ip.com/free/dbip-country-lite-{month}.csv.gz"
MAGIC = b"ZGC1"
V6_SHIFT = 80  # keep the upper 48 bits of an IPv6 address
MAX_BYTES = 3 * 1024 * 1024


def fetch(url: str, attempts: int = 3) -> bytes | None:
    """GET with retries; None when the file does not exist yet (HTTP 404)."""
    request = urllib.request.Request(url, headers={"User-Agent": "zapret-kvn-android-geoip"})
    for attempt in range(1, attempts + 1):
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            if error.code == 404:
                return None
            if attempt == attempts:
                raise SystemExit(f"Cannot download {url}: HTTP {error.code}") from None
        except (OSError, urllib.error.URLError) as error:
            if attempt == attempts:
                raise SystemExit(f"Cannot reach {url} after {attempts} attempts: {error}") from None
        time.sleep(5 * attempt)
    return None


def previous_month(month: str) -> str:
    year, number = map(int, month.split("-"))
    year, number = (year - 1, 12) if number == 1 else (year, number - 1)
    return f"{year:04d}-{number:02d}"


def varint(value: int) -> bytes:
    out = bytearray()
    while value >= 0x80:
        out.append((value & 0x7F) | 0x80)
        value >>= 7
    out.append(value)
    return bytes(out)


def read_varint(data: bytes, offset: int) -> tuple[int, int]:
    result = shift = 0
    while True:
        byte = data[offset]
        offset += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, offset
        shift += 7


def parse_rows(text_rows) -> tuple[list[tuple[int, str | None]], list[tuple[int, str | None]]]:
    """(start, country-or-None) points for IPv4 and IPv6, sorted, one per input row."""
    v4: list[tuple[int, str | None]] = []
    v6: list[tuple[int, str | None]] = []
    for row in text_rows:
        if len(row) < 3:
            continue
        first, _last, code = row[0].strip(), row[1].strip(), row[2].strip().upper()
        country = code if len(code) == 2 and code.isalpha() and code != "ZZ" else None
        if ":" in first:
            v6.append((int(ipaddress.IPv6Address(first)) >> V6_SHIFT, country))
        else:
            v4.append((int(ipaddress.IPv4Address(first)), country))
    v4.sort(key=lambda item: item[0])
    v6.sort(key=lambda item: item[0])
    return v4, v6


def fold(points: list[tuple[int, str | None]]) -> list[tuple[int, str]]:
    """Merge neighbours with one country; fold unknown ranges into the previous country."""
    out: list[tuple[int, str]] = []
    for start, country in points:
        if country is None:
            continue
        if out and out[-1][0] == start:
            out[-1] = (start, country)
        elif not out or out[-1][1] != country:
            out.append((start, country))
    # A later entry sharing a start replaced an earlier one; a replacement can equal its predecessor.
    merged: list[tuple[int, str]] = []
    for start, country in out:
        if not merged or merged[-1][1] != country:
            merged.append((start, country))
    return merged


def encode_stream(entries: list[tuple[int, str]], index: dict[str, int]) -> bytes:
    out = bytearray()
    previous = 0
    for start, country in entries:
        out += varint(start - previous)
        out.append(index[country])
        previous = start
    return bytes(out)


def build(v4: list[tuple[int, str]], v6: list[tuple[int, str]], month: str) -> bytes:
    codes = sorted({country for _, country in v4} | {country for _, country in v6})
    if not 0 < len(codes) <= 255:
        raise SystemExit(f"Unexpected country count: {len(codes)}")
    index = {code: position for position, code in enumerate(codes, start=1)}
    s4 = encode_stream(v4, index)
    s6 = encode_stream(v6, index)
    year, number = map(int, month.split("-"))
    header = MAGIC + struct.pack(">IB", year * 100 + number, len(codes))
    header += b"".join(code.encode("ascii") for code in codes)
    header += struct.pack(">IIII", len(v4), len(s4), len(v6), len(s6))
    return header + s4 + s6


def decode(blob: bytes):
    """Reference decoder used for the self-check; mirrors the Kotlin reader."""
    if blob[:4] != MAGIC:
        raise SystemExit("Bad magic")
    month, count = struct.unpack_from(">IB", blob, 4)
    offset = 9
    codes = [blob[offset + 2 * i: offset + 2 * i + 2].decode("ascii") for i in range(count)]
    offset += 2 * count
    n4, b4, n6, b6 = struct.unpack_from(">IIII", blob, offset)
    offset += 16

    def stream(length: int, entries: int):
        data = blob[offset_holder[0]: offset_holder[0] + length]
        offset_holder[0] += length
        position = start = 0
        result = []
        for _ in range(entries):
            delta, position = read_varint(data, position)
            start += delta
            result.append((start, codes[data[position] - 1]))
            position += 1
        if position != length:
            raise SystemExit("Stream length mismatch")
        return result

    offset_holder = [offset]
    return month, stream(b4, n4), stream(b6, n6)


def lookup(entries: list[tuple[int, str]], key: int) -> str | None:
    low, high, found = 0, len(entries) - 1, None
    while low <= high:
        mid = (low + high) // 2
        if entries[mid][0] <= key:
            found = entries[mid][1]
            low = mid + 1
        else:
            high = mid - 1
    return found


def self_check(blob: bytes, source_rows: list[list[str]]) -> None:
    _month, v4, v6 = decode(blob)
    # A /48 that holds ranges of several countries is rounded to one of them by design.
    mixed: set[int] = set()
    seen: dict[int, str] = {}
    for row in source_rows:
        if ":" not in row[0]:
            continue
        key = int(ipaddress.IPv6Address(row[0])) >> V6_SHIFT
        country = row[2].upper()
        if seen.setdefault(key, country) != country:
            mixed.add(key)
        end = int(ipaddress.IPv6Address(row[1])) >> V6_SHIFT
        if end != key and end not in seen:
            seen[end] = country
        elif end != key and seen[end] != country:
            mixed.add(end)
    step = max(1, len(source_rows) // 4000)
    checked = 0
    for row in source_rows[::step]:
        first, last, code = row[0], row[1], row[2].upper()
        if code == "ZZ":
            continue
        for address in (first, last):
            ip = ipaddress.ip_address(address)
            if ip.version == 4:
                got = lookup(v4, int(ip))
            else:
                key = int(ip) >> V6_SHIFT
                if key in mixed:
                    continue
                got = lookup(v6, key)
            if got != code:
                raise SystemExit(f"Self-check failed: {address} -> {got}, expected {code}")
            checked += 1
    print(f"Self-check passed on {checked} sampled addresses.")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check_committed() -> None:
    manifest = json.loads(MANIFEST.read_text())
    if sha256(BIN) != manifest["sha256"] or BIN.stat().st_size != manifest["bytes"]:
        raise SystemExit("GeoIP asset does not match its manifest")
    print(f"GeoIP asset matches manifest ({manifest['month']}, {manifest['bytes']} bytes).")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--month", help="YYYY-MM of the DB-IP file (default: current UTC month, then the previous one)")
    parser.add_argument("--input", type=Path, help="already downloaded dbip-country-lite-*.csv.gz (needs --month)")
    parser.add_argument("--check", action="store_true", help="only verify the committed asset against the manifest")
    args = parser.parse_args()
    if args.check:
        check_committed()
        return 0

    if args.input:
        if not args.month:
            parser.error("--input needs --month")
        month, raw, url = args.month, args.input.read_bytes(), URL.format(month=args.month)
    else:
        month = args.month or datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m")
        candidates = [month] if args.month else [month, previous_month(month)]
        raw = None
        for candidate in candidates:
            url = URL.format(month=candidate)
            print(f"Downloading {url}")
            raw = fetch(url)
            if raw is not None:
                month = candidate
                break
        if raw is None:
            raise SystemExit(f"DB-IP file not found for: {', '.join(candidates)}")

    try:
        text = gzip.decompress(raw).decode("utf-8")
    except (OSError, EOFError, UnicodeDecodeError) as error:
        raise SystemExit(f"Not a gzip CSV: {error}") from None
    rows = [row for row in csv.reader(text.splitlines()) if row]
    v4_points, v6_points = parse_rows(rows)
    if len(v4_points) < 100_000 or len(v6_points) < 50_000:
        raise SystemExit(f"Suspiciously small source: {len(v4_points)} IPv4 / {len(v6_points)} IPv6 rows")
    v4, v6 = fold(v4_points), fold(v6_points)
    blob = build(v4, v6, month)
    if len(blob) > MAX_BYTES:
        raise SystemExit(f"Asset is {len(blob)} bytes, over the {MAX_BYTES} budget")
    self_check(blob, rows)

    ASSETS.mkdir(parents=True, exist_ok=True)
    BIN.write_bytes(blob)
    manifest = {
        "version": 1,
        "format": "ZGC1",
        "database": "DB-IP IP to Country Lite",
        "month": month,
        "source": url,
        "source_sha256": hashlib.sha256(raw).hexdigest(),
        "license": "CC-BY-4.0",
        "attribution": "IP Geolocation by DB-IP",
        "attribution_url": "https://db-ip.com",
        "file": "country.bin",
        "sha256": hashlib.sha256(blob).hexdigest(),
        "bytes": len(blob),
        "ipv4_entries": len(v4),
        "ipv6_entries": len(v6),
        "ipv6_prefix_bits": 128 - V6_SHIFT,
        "countries": len({c for _, c in v4} | {c for _, c in v6}),
    }
    MANIFEST.write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Wrote {BIN.relative_to(ROOT)}: {len(blob)} bytes, {len(v4)} IPv4 + {len(v6)} IPv6 entries, {month}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
