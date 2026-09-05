#!/system/bin/sh
# FACC - Faa App Cache Cleaner
# customize.sh : dijalankan Magisk/KernelSU saat instalasi
# Prinsip: fail-safe, jangan pernah merusak sistem saat install gagal.

SKIPUNZIP=0

ui_print "- FACC - Faa App Cache Cleaner"
ui_print "- by Faa Ramadhan"
ui_print "- Membersihkan cache dengan aman (tanpa pm clear)"

# --- Validasi environment ---
if [ -z "$MODPATH" ]; then
  ui_print "! FATAL: MODPATH kosong, instalasi dibatalkan."
  abort "MODPATH tidak terdeteksi"
fi

# --- Android version check (minimal 7.0, API 24) ---
API=$(getprop ro.build.version.sdk 2>/dev/null)
if [ -n "$API" ] && [ "$API" -lt 24 ]; then
  ui_print "! Peringatan: API $API terdeteksi, FACC butuh minimal API 24."
fi

# --- Set permission struktur module ---
ui_print "- Menyiapkan permission..."

set_perm_recursive "$MODPATH/system/bin" 0 0 0755 0755
set_perm_recursive "$MODPATH/common" 0 0 0755 0644
set_perm_recursive "$MODPATH/webroot" 0 0 0755 0644

# File executable utama
set_perm "$MODPATH/system/bin/facc" 0 0 0755
set_perm "$MODPATH/common/core.sh" 0 0 0644
set_perm "$MODPATH/common/logger.sh" 0 0 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/customize.sh" 0 0 0755

# --- Siapkan direktori persistent di luar MODPATH ---
# Config & state disimpan di /data/adb/facc agar tidak hilang saat update module
PERSIST_DIR="/data/adb/facc"
mkdir -p "$PERSIST_DIR" 2>/dev/null
[ -f "$MODPATH/config/facc.conf" ] && [ ! -f "$PERSIST_DIR/facc.conf" ] && {
  cp -af "$MODPATH/config/facc.conf" "$PERSIST_DIR/facc.conf" 2>/dev/null
  ui_print "- Config default disalin ke $PERSIST_DIR/facc.conf"
}

# --- Siapkan direktori log ---
mkdir -p /sdcard/facc-log/archive 2>/dev/null || mkdir -p /data/media/0/facc-log/archive 2>/dev/null

# --- Info ---
ui_print "- FACC terinstal. Jalankan 'su -c facc' di terminal."
ui_print "- WebUI tersedia di aplikasi manager (MMRL / WebUI Next)."
ui_print "- Selesai."
