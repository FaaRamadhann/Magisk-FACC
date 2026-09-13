#!/usr/bin/env python3
"""Pack repo ini menjadi zip module Magisk (anti-bug backslash).

Repo root = module root (mirip Magisk-Python): module.prop ada di sini.

Pakai:
    python zip.py              -> output build/FACC-v<version>.zip
    python zip.py -o out.zip   -> output build/out.zip (atau path absolut)
    python zip.py --no-version -> output build/FACC.zip tanpa versi

Hasil TIDAK di-commit ke git (lihat .gitignore: build/, *.zip).
Distribusi via upload manual ke GitHub Releases.

Catatan backslash (Windows):
    - Script ini TIDAK memakai string path Windows mentah seperti
      "D:\\GitHub-..." di dalam kode. Semua path dibangun dari
      Path(__file__).parent, jadi tidak ada error escape backslash.
    - Entry di dalam zip selalu forward-slash (as_posix) agar terbaca
      Magisk/KernelSU. Ini bug umum kalau pakai os.path.join mentah.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys
import zipfile

# Direktori script ini (= repo root = module root).
# Pakai Path, bukan string "D:\..." -> bebas error backslash.
ROOT = pathlib.Path(__file__).resolve().parent
MODULE_DIR = ROOT

# File yang wajib ada sebelum di-pack.
REQUIRED = [
    "module.prop",
    "customize.sh",
    "uninstall.sh",
    "service.sh",
    "action.sh",
    "system/bin/facc",
    "common/core.sh",
    "common/logger.sh",
    "config/facc.conf",
    "webroot/index.html",
    "manager/manager.apk",
]

# File/dir yang dikecualikan dari zip (file dev, bukan bagian module).
# "FACC" = sisa folder kosong lama (struktur sebelum v1.0.4); abaikan bila ada.
# "build" = output zip lokal, jangan ikut ke-pack.
EXCLUDE_DIRS = {"temp", "__pycache__", ".git", ".hg", ".svn", "archive", "FACC", "build"}
EXCLUDE_FILES = {".DS_Store", "Thumbs.db", "zip.py", ".gitignore", ".gitattributes"}
EXCLUDE_SUFFIXES = {".pyc", ".pyo", ".zip"}

# File yang butuh bit executable di dalam zip (Magisk baca external_attr).
EXECUTABLES = {
    "customize.sh",
    "uninstall.sh",
    "service.sh",
    "action.sh",
    "system/bin/facc",
    "tests/test_facc.sh",
}


def read_version(module_dir: pathlib.Path) -> str:
    """Ambil version=vX dari module.prop. Fallback 1.0.0."""
    prop = module_dir / "module.prop"
    try:
        text = prop.read_text(encoding="utf-8")
    except OSError:
        return "1.0.0"
    m = re.search(r"^version\s*=\s*(.+?)\s*$", text, re.M)
    if not m:
        return "1.0.0"
    return m.group(1).lstrip("v").strip() or "1.0.0"


def should_skip(path: pathlib.Path, module_dir: pathlib.Path) -> bool:
    rel = path.relative_to(module_dir)
    if any(part in EXCLUDE_DIRS for part in rel.parts):
        return True
    # Module hanya include manager/manager.apk (hasil build.bat).
    # Source manager (src/, res/, AndroidManifest.xml, build.bat, keystore)
    # JANGAN ikut ke-pack ke zip module.
    if rel.parts and rel.parts[0] == "manager":
        if rel.as_posix() != "manager/manager.apk":
            return True
    if path.is_file():
        if path.name in EXCLUDE_FILES:
            return True
        if path.suffix in EXCLUDE_SUFFIXES:
            return True
    return False


def zip_info_for(path: pathlib.Path, arcname_posix: str) -> zipfile.ZipInfo:
    """Buat ZipInfo dengan forward-slash + permission unix yang benar."""
    zi = zipfile.ZipInfo(filename=arcname_posix)
    zi.compress_type = zipfile.ZIP_DEFLATED
    if path.is_dir():
        zi.filename += "/"  # penanda direktori, tetap forward-slash
        zi.external_attr = (0o755 << 16) | 0x10
    else:
        mode = 0o755 if arcname_posix in EXECUTABLES else 0o644
        zi.external_attr = mode << 16
    return zi


def build(module_dir: pathlib.Path, out_zip: pathlib.Path) -> pathlib.Path:
    if not module_dir.is_dir():
        sys.exit(f"Module dir tidak ketemu: {module_dir}")
    missing = [f for f in REQUIRED if not (module_dir / f).exists()]
    if missing:
        sys.exit("File wajib hilang: " + ", ".join(missing))

    # Jangan pack output zip ke dalam dirinya sendiri (kalau out di dalam FACC).
    out_zip = out_zip.resolve()
    files: list[pathlib.Path] = []
    for p in sorted(module_dir.rglob("*")):
        if p.resolve() == out_zip:
            continue
        if should_skip(p, module_dir):
            continue
        files.append(p)

    out_zip.parent.mkdir(parents=True, exist_ok=True)
    if out_zip.exists():
        out_zip.unlink()

    with zipfile.ZipFile(out_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        for path in files:
            # PENTING: as_posix() -> forward-slash, bukan backslash Windows.
            arc = path.relative_to(module_dir).as_posix()
            zi = zip_info_for(path, arc)
            if path.is_dir():
                zf.writestr(zi, "")
            else:
                zf.writestr(zi, path.read_bytes())

    # Validasi: pastikan tidak ada backslash di entry names.
    with zipfile.ZipFile(out_zip) as zf:
        bad = [n for n in zf.namelist() if "\\" in n]
        if bad:
            sys.exit(f"ZIP cacat, ada backslash di entry: {bad[:5]}")

    return out_zip


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Pack repo menjadi zip Magisk di build/.")
    ap.add_argument("-o", "--output", default=None,
                    help="Nama file output di build/ (default: FACC-v<version>.zip)")
    ap.add_argument("--no-version", action="store_true",
                    help="Output build/FACC.zip tanpa versi")
    args = ap.parse_args(argv)

    outdir = ROOT / "build"
    outdir.mkdir(parents=True, exist_ok=True)
    version = read_version(MODULE_DIR)
    if args.output:
        out = pathlib.Path(args.output)
        out = out if out.is_absolute() else (outdir / out.name)
    elif args.no_version:
        out = outdir / "FACC.zip"
    else:
        out = outdir / f"FACC-v{version}.zip"

    result = build(MODULE_DIR, out)
    size_kb = result.stat().st_size / 1024
    print(f"OK: {result.name} ({size_kb:.1f} KB) dari repo root")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
