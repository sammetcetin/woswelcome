#!/usr/bin/env python3
"""Dağıtım repo.json dosyasını çalıştığı GitHub deposuna bağlar."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


REPOSITORY_PATTERN = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-json", required=True, type=Path)
    parser.add_argument("--repository", required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not REPOSITORY_PATTERN.fullmatch(args.repository):
        raise SystemExit(f"Geçersiz GitHub depo kimliği: {args.repository!r}")

    metadata = json.loads(args.repo_json.read_text(encoding="utf-8"))
    metadata["pluginLists"] = [
        f"https://raw.githubusercontent.com/{args.repository}/builds/plugins.json"
    ]
    args.repo_json.write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
