#!/system/bin/sh
# FACC service.sh — dijalankan Magisk/KernelSU saat boot (late_start).
# Prinsip: JANGAN bikin scheduler duluan sebelum engine stabil — engine sudah
# stabil (core.sh), jadi service ini hanya loop: baca config -> tunggu -> eksekusi.

MODDIR=${0%/*}

# Tunggu boot selesai agar pm & storage siap
while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; do
  sleep 10
done
# Beri waktu tambahan agar /sdcard ter-mount
sleep 30

CONF_PERSIST="/data/adb/facc/facc.conf"
CONF_MOD="$MODDIR/config/facc.conf"
FACC_BIN="$MODDIR/system/bin/facc"
[ -x "$FACC_BIN" ] || FACC_BIN="facc"

# Pastikan config persist ada
if [ ! -f "$CONF_PERSIST" ] && [ -f "$CONF_MOD" ]; then
  mkdir -p /data/adb/facc 2>/dev/null
  cp -af "$CONF_MOD" "$CONF_PERSIST" 2>/dev/null
fi

# shellcheck source=/dev/null
[ -f "$MODDIR/common/logger.sh" ] && . "$MODDIR/common/logger.sh"

facc_log_info "FACC service started"

while true; do
  AUTO_CLEAN=1
  INTERVAL_MINUTES=30
  if [ -f "$CONF_PERSIST" ]; then
    # Config parser yang tahan malformed: hanya ambil baris KEY=angka
    v=$(grep -E "^AUTO_CLEAN=[01]" "$CONF_PERSIST" 2>/dev/null | tail -n1 | cut -d= -f2)
    [ -n "$v" ] && AUTO_CLEAN="$v"
    v=$(grep -E "^INTERVAL_MINUTES=[0-9]+" "$CONF_PERSIST" 2>/dev/null | tail -n1 | cut -d= -f2)
    [ -n "$v" ] && INTERVAL_MINUTES="$v"
    unset v
  fi
  # Validasi interval: 5 menit – 24 jam
  case "$INTERVAL_MINUTES" in ''|*[!0-9]*) INTERVAL_MINUTES=30 ;; esac
  [ "$INTERVAL_MINUTES" -lt 5 ] && INTERVAL_MINUTES=5
  [ "$INTERVAL_MINUTES" -gt 1440 ] && INTERVAL_MINUTES=1440

  if [ "$AUTO_CLEAN" = "1" ]; then
    MODDIR="$MODDIR" "$FACC_BIN" --clean >/dev/null 2>&1
    date "+%Y-%m-%d %H:%M:%S" > /data/adb/facc/last_run 2>/dev/null
    facc_log_info "Scheduler run done, next in ${INTERVAL_MINUTES}m"
  fi

  # Tidur per menit agar perubahan config cepat terbaca
  i=0
  while [ "$i" -lt "$INTERVAL_MINUTES" ]; do
    sleep 60
    i=$((i + 1))
    # Baca ulang AUTO_CLEAN tiap menit; keluar cepat bila dimatikan/diubah
    v=$(grep -E "^AUTO_CLEAN=[01]" "$CONF_PERSIST" 2>/dev/null | tail -n1 | cut -d= -f2)
    [ -n "$v" ] && [ "$v" != "$AUTO_CLEAN" ] && break
    unset v
  done
  unset i
done
