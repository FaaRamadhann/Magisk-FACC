# FACC — Faa App Cache Cleaner

Pembersih cache aplikasi yang **aman**: tanpa `pm clear`, tanpa hapus data,
hanya isi direktori `cache/`. Ada CLI, scheduler otomatis, log lengkap, dan WebUI.

## Struktur

```
FACC/
├── module.prop          # id facc, author Faa Ramadhan
├── customize.sh         # installer Magisk/KernelSU
├── uninstall.sh         # cleanup saat module dihapus
├── action.sh            # tombol Action di manager
├── service.sh           # scheduler auto-clean (boot)
├── system/bin/facc      # CLI utama (CLI First)
├── common/
│   ├── core.sh          # engine: validate, safe_path, size, clean, verify
│   └── logger.sh        # log /sdcard/facc-log/
├── config/facc.conf     # default; aktif di /data/adb/facc/facc.conf
├── webroot/             # WebUI (MMRL / WebUI Next / KernelSU)
│   ├── index.html
│   ├── css/style.css
│   └── js/app.js        # bridge -> `facc --* --json`
└── tests/test_facc.sh   # safety tests
```

## Prinsip

- **Safe**: validasi path → abort bila unsafe. Tidak ada fallback `rm -rf` ke path tak jelas.
- **CLI First**: WebUI & scheduler hanya memanggil `facc`. Tidak ada logika ganda.
- **Logging**: `/sdcard/facc-log/facc.log`, `latest.log`, `archive/`, format `[TIME] [LEVEL] MESSAGE`.

## Pakai (di HP, sebagai root)

```sh
su -c facc                 # menu interaktif
su -c "facc --scan"        # pindai saja
su -c "facc --clean"       # bersihkan semua
su -c "facc --clean com.android.chrome"
su -c "facc --status --json"
su -c "facc --logs --lines 100"
```

## Config scheduler

Edit `/data/adb/facc/facc.conf`:

```
AUTO_CLEAN=1
INTERVAL_MINUTES=30
```

Scheduler baca ulang tiap menit. Minimal 5, maksimal 1440.

## Build zip (di PC)

```powershell
Compress-Archive -Path FACC/* -DestinationPath FACC-v1.0.0.zip -Force
```

Install zip via Magisk / KernelSU / MMRL.

## Test

```sh
sh tests/test_facc.sh
```

Mencakup: package tanpa cache, invalid package, path traversal,
code_cache/files ditolak, hapus hanya isi cache, malformed config, human-size.
