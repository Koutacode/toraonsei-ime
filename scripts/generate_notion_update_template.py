#!/usr/bin/env python3
"""
Generate a Markdown template for Notion release updates.

Usage:
  python scripts/generate_notion_update_template.py --release-url <URL>
  python scripts/generate_notion_update_template.py --release-url <URL> --apk-url <URL> --output tmp/notion_update.md
  python scripts/generate_notion_update_template.py --release-url <URL> --highlight "変換候補UIを改善"
"""

from __future__ import annotations

import argparse
import subprocess
from datetime import datetime
from pathlib import Path


def git_value(*args: str) -> str:
    try:
        return subprocess.check_output(["git", *args], text=True).strip()
    except Exception:
        return "unknown"


def build_markdown(
    app_name: str,
    release_url: str,
    apk_url: str,
    highlights: list[str],
    checks: list[str],
) -> str:
    now = datetime.now().strftime("%Y-%m-%d %H:%M")
    commit = git_value("rev-parse", "--short", "HEAD")
    branch = git_value("rev-parse", "--abbrev-ref", "HEAD")

    if not highlights:
        highlights = [
            "変更点をここに記載",
            "実機確認した結果をここに記載",
        ]
    if not checks:
        checks = [
            "LINEでIME表示 -> 入力 -> 送信",
            "ChatGPTアプリで音声入力 -> 変換 -> 確定",
            "Fold開閉と縦横回転でレイアウト確認",
        ]

    lines = [
        f"# {app_name} 更新メモ",
        "",
        "## リリース情報",
        f"- 更新日時: {now}",
        f"- ブランチ: {branch}",
        f"- 最新コミット: `{commit}`",
        f"- リリースURL: {release_url or '（ここにURL）'}",
        f"- APK直リンク: {apk_url or '（ここにURL）'}",
        "",
        "## 主な変更",
    ]
    lines.extend(f"- {item}" for item in highlights)

    lines.append("")
    lines.append("## 実機確認")
    lines.extend(f"- {item}" for item in checks)

    lines.append("")
    lines.append("## メモ")
    lines.append("- 必要なら追記")
    lines.append("")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--app-name", default="ToraOnsei", help="Page title prefix")
    parser.add_argument("--release-url", default="", help="GitHub release page URL")
    parser.add_argument("--apk-url", default="", help="Direct APK download URL")
    parser.add_argument(
        "--highlight",
        action="append",
        default=[],
        help="Add one change item (repeatable)",
    )
    parser.add_argument(
        "--check",
        action="append",
        default=[],
        help="Add one verification item (repeatable)",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=None,
        help="Optional output markdown path. If omitted, prints to stdout.",
    )
    args = parser.parse_args()

    markdown = build_markdown(
        app_name=args.app_name,
        release_url=args.release_url,
        apk_url=args.apk_url,
        highlights=args.highlight,
        checks=args.check,
    )

    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(markdown, encoding="utf-8", newline="\n")
        print(f"[done] wrote {args.output}")
    else:
        print(markdown)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
