#!/usr/bin/env python3
"""CloudStream dağıtım indeksini ve .cs3 paketlerini doğrular."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import zipfile
from pathlib import Path
from urllib.parse import urlparse


REQUIRED_PACKAGE_FILES = {"manifest.json", "classes.dex"}
REQUIRED_PLUGIN_FIELDS = {
    "fileHash",
    "fileSize",
    "internalName",
    "name",
    "status",
    "url",
    "version",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--plugins-json", type=Path, required=True)
    parser.add_argument("--packages-dir", type=Path, required=True)
    return parser.parse_args()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as package:
        for chunk in iter(lambda: package.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_package(package: Path, plugin: dict[str, object]) -> list[str]:
    errors: list[str] = []

    if not package.is_file():
        return [f"{plugin.get('internalName')}: paket bulunamadı: {package.name}"]

    expected_size = plugin.get("fileSize")
    if expected_size != package.stat().st_size:
        errors.append(
            f"{package.name}: fileSize uyuşmuyor "
            f"({expected_size} != {package.stat().st_size})"
        )

    expected_hash = str(plugin.get("fileHash", ""))
    actual_hash = f"sha256-{sha256(package)}"
    if expected_hash != actual_hash:
        errors.append(f"{package.name}: fileHash uyuşmuyor")

    try:
        with zipfile.ZipFile(package) as archive:
            corrupt_file = archive.testzip()
            if corrupt_file:
                errors.append(f"{package.name}: bozuk arşiv üyesi: {corrupt_file}")

            members = set(archive.namelist())
            missing_members = REQUIRED_PACKAGE_FILES - members
            if missing_members:
                errors.append(
                    f"{package.name}: eksik paket üyeleri: "
                    f"{', '.join(sorted(missing_members))}"
                )
            elif "manifest.json" in members:
                manifest = json.loads(archive.read("manifest.json"))
                if manifest.get("name") != plugin.get("internalName"):
                    errors.append(f"{package.name}: manifest adı indeksle uyuşmuyor")
                if manifest.get("version") != plugin.get("version"):
                    errors.append(f"{package.name}: manifest sürümü indeksle uyuşmuyor")
    except (OSError, zipfile.BadZipFile, json.JSONDecodeError) as error:
        errors.append(f"{package.name}: paket okunamadı: {error}")

    return errors


def main() -> int:
    args = parse_args()
    plugins = json.loads(args.plugins_json.read_text(encoding="utf-8"))

    if not isinstance(plugins, list) or not plugins:
        print("plugins.json boş veya liste değil", file=sys.stderr)
        return 1

    errors: list[str] = []
    indexed_packages: set[str] = set()
    internal_names: set[str] = set()

    for index, plugin in enumerate(plugins):
        if not isinstance(plugin, dict):
            errors.append(f"Kayıt {index}: nesne değil")
            continue

        missing_fields = REQUIRED_PLUGIN_FIELDS - plugin.keys()
        if missing_fields:
            errors.append(
                f"Kayıt {index}: eksik alanlar: {', '.join(sorted(missing_fields))}"
            )
            continue

        internal_name = str(plugin["internalName"])
        if internal_name in internal_names:
            errors.append(f"{internal_name}: yinelenen internalName")
        internal_names.add(internal_name)

        package_name = Path(urlparse(str(plugin["url"])).path).name
        if package_name in indexed_packages:
            errors.append(f"{package_name}: yinelenen paket URL'si")
        indexed_packages.add(package_name)

        if package_name != f"{internal_name}.cs3":
            errors.append(
                f"{internal_name}: URL paket adı beklenenden farklı: {package_name}"
            )

        errors.extend(validate_package(args.packages_dir / package_name, plugin))

    actual_packages = {path.name for path in args.packages_dir.glob("*.cs3")}
    for extra_package in sorted(actual_packages - indexed_packages):
        errors.append(f"İndekste olmayan paket: {extra_package}")

    if errors:
        for error in errors:
            print(f"HATA: {error}", file=sys.stderr)
        return 1

    print(f"Dağıtım doğrulandı: {len(plugins)} indeks kaydı, {len(actual_packages)} paket")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
