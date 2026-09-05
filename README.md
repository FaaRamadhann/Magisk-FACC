# Magisk-FACC
Pembersih cache aplikasi yang aman. Tanpa hapus data, tanpa pm clear. Ada CLI, auto-clean terjadwal, log lengkap, dan WebUI.

**FACC = Faa App Cache Cleaner** — Magisk / KernelSU / APatch module oleh Faa Ramadhan.

## Prinsip

- **Safe** — validasi tiap target path, gagal validasi = abort. Tidak pernah `pm clear`, tidak pernah hapus app data, hanya isi direktori `cache/`.
- **CLI First** — semua kemampuan lewat `facc`. WebUI & scheduler hanya memanggil CLI, tidak ada logika ganda.
- **Automation** — service terjadwal (`AUTO_CLEAN`, `INTERVAL_MINUTES`).
- **Logging** — `/sdcard/facc-log/` format `[TIME] [LEVEL] MESSAGE`.

## Install

1. Download zip dari [Releases](../../releases) (`FACC-vX.Y.Z.zip`).
2. Flash via Magisk / KernelSU / APatch manager, atau:
   `su -c 'magisk --install-module /sdcard/FACC-vX.Y.Z.zip'`
3. Reboot.

Butuh WebUI? Buka module dari aplikasi **MMRL** atau **WebUI Next** (Magisk official manager tidak mendukung WebUI).

## Pakai

```sh
su -c facc                              # menu interaktif
su -c "facc --scan"                     # pindai saja
su -c "facc --clean"                    # bersihkan semua
su -c "facc --clean com.android.chrome" # satu aplikasi
su -c "facc --status --json"            # untuk WebUI / scripting
su -c "facc --logs --lines 100"
```

Config scheduler: edit `/data/adb/facc/facc.conf`

```
AUTO_CLEAN=1
INTERVAL_MINUTES=30
```

## Struktur repo

```
FACC/                 <- isi module (di-pack jadi zip)
├── module.prop
├── customize.sh / uninstall.sh / service.sh / action.sh
├── system/bin/facc   <- CLI utama
├── common/           <- core engine + logger (independen dari UI)
├── config/           <- default config
├── webroot/          <- WebUI (HTML/CSS/JS, via exec bridge)
└── tests/            <- safety tests
build.py              <- packer: python build.py -> FACC-vX.Y.Z.zip
```

## Build sendiri

```sh
python build.py
```

## Test

```sh
# di HP (root):
sh /data/adb/modules/facc/tests/test_facc.sh
# harus: N lulus, 0 gagal
```

## Lisensi

MIT — lihat [LICENSE](LICENSE).
