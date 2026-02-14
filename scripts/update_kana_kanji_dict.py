#!/usr/bin/env python3
"""
Generate app/src/main/assets/kana_kanji_base.tsv from SKK dictionaries.

Usage:
  python scripts/update_kana_kanji_dict.py
  python scripts/update_kana_kanji_dict.py --max-candidates 8
  python scripts/update_kana_kanji_dict.py --output app/src/main/assets/kana_kanji_base.tsv
"""

from __future__ import annotations

import argparse
import gzip
import re
import tempfile
import urllib.request
from collections import OrderedDict
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable

DEFAULT_SOURCE_FILES = (
    "SKK-JISYO.S.gz",
    "SKK-JISYO.M.gz",
    "SKK-JISYO.L.gz",
    "SKK-JISYO.fullname.gz",
    "SKK-JISYO.propernoun.gz",
    "SKK-JISYO.jinmei.gz",
    "SKK-JISYO.station.gz",
    "SKK-JISYO.ML.gz",
    "SKK-JISYO.assoc.gz",
    "SKK-JISYO.geo.gz",
    "SKK-JISYO.law.gz",
    "SKK-JISYO.okinawa.gz",
    "SKK-JISYO.china_taiwan.gz",
    "SKK-JISYO.wrong.gz",
    "SKK-JISYO.pubdic+.gz",
)
SOURCE_BASE_URL = "https://skk-dev.github.io/dict/"

ENTRY_PATTERN = re.compile(r"^([^\s]+)\s+/(.+)/$")
OKURI_KEY_PATTERN = re.compile(r"^[ぁ-ゖァ-ヺー]+[a-z]$")
KANA_KEY_PATTERN = re.compile(r"^[ぁ-ゖー]{1,24}$")
CONTENT_PATTERN = re.compile(r"[\u4e00-\u9fff\u3040-\u30ffA-Za-z0-9]")


def katakana_to_hiragana(text: str) -> str:
    out = []
    for ch in text:
        code = ord(ch)
        if 0x30A1 <= code <= 0x30F6:
            out.append(chr(code - 0x60))
        else:
            out.append(ch)
    return "".join(out)


def parse_candidate(candidate: str) -> str:
    # SKK dict has inline notes after ';'
    raw = candidate.split(";", 1)[0].strip()
    return raw


def iter_dictionary_lines(gz_path: Path) -> Iterable[str]:
    with gzip.open(gz_path, "rb") as f:
        for raw in f:
            # SKK dictionaries are euc-jp.
            line = raw.decode("euc_jp", errors="ignore").strip()
            if line:
                yield line


def download_sources(download_dir: Path, source_files: list[str]) -> tuple[list[Path], list[str]]:
    paths: list[Path] = []
    failed: list[str] = []
    for name in source_files:
        url = SOURCE_BASE_URL + name
        dst = download_dir / name
        print(f"[download] {url}")
        try:
            with urllib.request.urlopen(url, timeout=30) as src, open(dst, "wb") as out:
                out.write(src.read())
            paths.append(dst)
        except Exception as exc:
            failed.append(name)
            print(f"[warn] download failed: {name} ({exc})")
    return paths, failed


def build_mapping(source_paths: list[Path], max_candidates: int) -> OrderedDict[str, list[str]]:
    mapping: OrderedDict[str, list[str]] = OrderedDict()

    for src in source_paths:
        print(f"[parse] {src.name}")
        for line in iter_dictionary_lines(src):
            if line.startswith(";"):
                continue
            m = ENTRY_PATTERN.match(line)
            if not m:
                continue

            key = m.group(1)
            if len(key) > 1 and OKURI_KEY_PATTERN.match(key):
                key = key[:-1]
            key = katakana_to_hiragana(key)
            if not KANA_KEY_PATTERN.match(key):
                continue

            arr = mapping.setdefault(key, [])
            if len(arr) >= max_candidates:
                continue

            body = m.group(2)
            for p in body.split("/"):
                if len(arr) >= max_candidates:
                    break
                if not p:
                    continue
                cand = parse_candidate(p)
                if not cand:
                    continue
                if len(cand) > 28:
                    continue
                if cand.startswith(("(", "[", "http")):
                    continue
                if not CONTENT_PATTERN.search(cand):
                    continue
                if cand not in arr:
                    arr.append(cand)

    return mapping


def write_tsv(mapping: OrderedDict[str, list[str]], output: Path, source_files: list[str]) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    generated_at = datetime.now(timezone.utc).astimezone().strftime("%Y-%m-%d")
    source_label = ", ".join(
        file_name.removeprefix("SKK-JISYO.").removesuffix(".gz")
        for file_name in source_files
    )
    with output.open("w", encoding="utf-8", newline="\n") as w:
        w.write(f"# source: SKK dictionaries ({source_label})\n")
        w.write(f"# generated_at: {generated_at}\n")
        w.write("# format: reading<TAB>candidate1|candidate2|...\n")
        for key in sorted(mapping.keys()):
            vals = mapping[key]
            if vals:
                w.write(f"{key}\t{'|'.join(vals)}\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("app/src/main/assets/kana_kanji_base.tsv"),
        help="Output TSV path",
    )
    parser.add_argument(
        "--max-candidates",
        type=int,
        default=8,
        help="Max candidates per reading key",
    )
    parser.add_argument(
        "--sources",
        type=str,
        default=",".join(DEFAULT_SOURCE_FILES),
        help="Comma-separated SKK dictionary file names to fetch from skk-dev",
    )
    args = parser.parse_args()

    if args.max_candidates < 1 or args.max_candidates > 20:
        raise SystemExit("--max-candidates must be in [1, 20]")
    source_files = [
        item.strip()
        for item in args.sources.split(",")
        if item.strip()
    ]
    if not source_files:
        raise SystemExit("--sources must include at least one SKK dictionary file name")
    if any("/" in item or "\\" in item for item in source_files):
        raise SystemExit("--sources must be file names only (no path separators)")
    if any(not item.startswith("SKK-JISYO.") or not item.endswith(".gz") for item in source_files):
        raise SystemExit("--sources entries must look like SKK-JISYO.<name>.gz")

    with tempfile.TemporaryDirectory(prefix="skk_dict_") as tmp:
        source_paths, failed_sources = download_sources(Path(tmp), source_files=source_files)
        if not source_paths:
            raise SystemExit("all downloads failed")
        mapping = build_mapping(source_paths, max_candidates=args.max_candidates)
        downloaded_labels = [path.name for path in source_paths]
        write_tsv(mapping, args.output, source_files=downloaded_labels)

    print(f"[done] entries={len(mapping)} output={args.output}")
    print(f"[done] size={args.output.stat().st_size} bytes")
    if failed_sources:
        print(f"[done] skipped={','.join(failed_sources)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
