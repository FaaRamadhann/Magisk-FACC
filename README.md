# Faa App Cache Cleaner (FACC)

[![Magisk](https://img.shields.io/badge/Magisk-Module-00af9c)](https://github.com/topjohnwu/Magisk) [![KernelSU](https://img.shields.io/badge/KernelSU-Module-323136)](https://github.com/tiann/KernelSU) [![APatch](https://img.shields.io/badge/APatch-Module-334d83)](https://github.com/apatch/apatch) [![License](https://img.shields.io/github/license/FaaRamadhann/Magisk-FACC)](LICENSE)

---

## Apa itu FACC?

Modul **Magisk / KernelSU / APatch** untuk membersihkan **cache aplikasi dengan aman** — tanpa `pm clear`, tanpa hapus app data, hanya isi direktori `cache/`. Punya CLI (`facc`), auto-clean terjadwal, log lengkap, dan WebUI.

## Fitur

- **Aman** — tiap target path divalidasi, gagal validasi = abort. `code_cache`/`files`/data lain tidak pernah disentuh
- **CLI `facc`** — menu interaktif + argumen (`--scan`, `--clean`, `--status`, `--logs`, `--version`) + output `--json` buat WebUI/scripting
- **Auto-clean terjadwal** — service boot, config `AUTO_CLEAN` / `INTERVAL_MINUTES`
- **Logging** — `/sdcard/facc-log/` format `[TIME] [LEVEL] MESSAGE` + rotasi arsip
- **WebUI** — dashboard, scan/clean per aplikasi, log viewer, config scheduler (via exec bridge `ksu`/`koush`/`kernelsu`/`mmrl`)
- **`zip.py`** untuk mem-packing modul jadi `.zip` (anti-bug backslash `\`)

## Persyaratan

- **Android:** 7.0+ (API 24+)
- **Root:** Magisk / KernelSU / APatch

> Butuh WebUI? Buka module dari aplikasi **MMRL** atau **WebUI Next** — Magisk official manager tidak mendukung WebUI. Kalau indikator pojok kanan merah (mock), izinkan "JavaScript KernelSU API" untuk module ini (MMRL v5.30+).

---

## Cara Install

Ada dua cara: **via PC (Windows/Linux)** atau **via Termux di HP**.

### A. Via PC (Windows / Linux) — pakai `adb push`

**Langkah 0 — Siapkan (clone + build zip):**

Prasyarat: sudah ada `git` dan `python 3` (versi apa pun) di PC.

```
# 1. Clone repo ini
git clone https://github.com/FaaRamadhann/Magisk-FACC.git
cd Magisk-FACC

# 2. Pack jadi zip (atau langsung pakai FACC-vX.Y.Z.zip yang sudah ada di repo)
python zip.py
```

Perintah `python zip.py` menghasilkan file:

```
build/FACC-v1.0.4.zip
```

**Langkah 1 — Hubungkan HP ke PC:**

Aktifkan **USB Debugging** di HP (Developer Options), lalu colok USB.

```
# Cek HP terdeteksi
adb devices
# Harus muncul status "device"
```

**Langkah 2 — Push zip ke HP:**

```
adb push build/FACC-v1.0.4.zip /sdcard/
```

**Langkah 3 — Install lewat manager root (dari HP):**

Buka aplikasi **Magisk / KernelSU / APatch / MMRL** → **Modul** → **Install dari penyimpanan** → pilih `FACC-v1.0.4.zip`.

Atau via terminal/shell (root):

```
# masuk shell adb
adb shell
# lalu jalankan sebagai root
su -c 'magisk --install-module /sdcard/FACC-v1.0.4.zip'
```

**Langkah 4 — Reboot**, lalu cek:

```
su -c 'facc --status'
```

---

### B. Via Termux (di HP, tanpa PC)

Prasyarat: sudah ada **Termux** dan akses **root** (`su`).

**Langkah 1 — Install git & clone:**

```
pkg install -y git python
git clone https://github.com/FaaRamadhann/Magisk-FACC.git
cd Magisk-FACC
```

**Langkah 2 — Build zip:**

```
python zip.py
```

**Langkah 3 — Pindahkan zip ke penyimpanan (agar bisa dipilih manager):**

```
cp build/FACC-v1.0.4.zip /sdcard/
```

**Langkah 4 — Install via manager root:**

Buka aplikasi **Magisk / KernelSU / APatch / MMRL** → **Modul** → **Install dari penyimpanan** → pilih zip.

Atau lewat Termux dengan root:

```
su -c 'magisk --install-module /sdcard/FACC-v1.0.4.zip'
```

**Langkah 5 — Reboot**, lalu cek:

```
su -c 'facc --status'
```

---

## Cara Pakai

```sh
su -c facc                              # menu interaktif
su -c "facc --scan"                     # pindai saja
su -c "facc --clean"                    # bersihkan semua
su -c "facc --clean com.android.chrome" # satu aplikasi
su -c "facc --status --json"            # untuk WebUI / scripting
su -c "facc --logs --lines 100"
```

Config scheduler — tanpa edit manual, via CLI:

```sh
su -c "facc --autoclean 60"  # atau: facc -ac 60 (1 jam)
su -c "facc -ac 120"         # 2 jam (batas: 5-1440 menit = maks 24 jam)
su -c "facc --acon"          # aktifkan auto-clean
su -c "facc --acoof"         # matikan auto-clean
su -c "facc --acstatus"      # status on/off + interval
su -c "facc --lclean"        # waktu cleanup terakhir
su -c "facc --clogs"         # reset/hapus log
```

(Masih bisa edit manual: `/data/adb/facc/facc.conf` — scheduler baca ulang tiap menit.)

## Struktur Modul

```
Magisk-FACC/
├── module.prop            # Metadata modul (id facc, Faa Ramadhan)
├── customize.sh           # Instalasi: permission + config + log dir
├── uninstall.sh           # Cleanup saat module dihapus
├── action.sh              # Tombol Action di manager
├── service.sh             # Scheduler auto-clean (jalan saat boot)
├── zip.py                 # Packing repo jadi .zip
├── LICENSE
├── system/bin/facc        # CLI utama (CLI First)
├── common/                # Engine: core.sh (validate/safe/size/clean/verify)
│                          # + logger.sh — independen dari UI
├── config/facc.conf       # Default config
├── webroot/               # WebUI (HTML/CSS/JS via exec bridge)
└── tests/test_facc.sh     # Safety tests (harus: N lulus, 0 gagal)
```

## Troubleshooting

Masalah | Solusi
`core.sh tidak ditemukan` | Reboot setelah install/update agar overlay aktif
WebUI mock (indikator merah) | Buka dari MMRL/WebUI Next, bukan browser; allow API di MMRL v5.30+
`facc` command not found | Jalankan sebagai root: `su -c facc`
Scheduler tidak jalan | Cek `/data/adb/facc/facc.conf` (`AUTO_CLEAN=1`) dan log `/sdcard/facc-log/`

## Lisensi

[MIT License](LICENSE) — © 2026 Faa Ramadhan
