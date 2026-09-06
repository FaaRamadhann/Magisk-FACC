#!/system/bin/sh
# test_facc.sh — unit-ish shell tests untuk FACC core (Fase 8).
# Aman dijalankan di mana saja: memakai FAKE_ROOT, tidak menyentuh /data asli.
# Cara pakai di Android:  su -c "sh /data/adb/modules/facc/tests/test_facc.sh"
# Cara pakai di Linux/PC: sh tests/test_facc.sh

PASS=0; FAIL=0
HERE="$(dirname "$0")"
# shellcheck source=/dev/null
. "$HERE/../common/core.sh" 2>/dev/null || . "$HERE/../../FACC/common/core.sh" 2>/dev/null || {
  echo "core.sh tidak ketemu"; exit 1
}

ok()   { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
assert0() { if "$@" >/dev/null 2>&1; then ok "$* -> 0"; else bad "$* -> expected 0"; fi; }
assert1() { if "$@" >/dev/null 2>&1; then bad "$* -> expected non-0"; else ok "$* -> non-0"; fi; }

echo "== FACC safety tests =="

echo "-- validate_package --"
assert0 validate_package com.android.chrome
assert0 validate_package com.instagram.android
assert1 validate_package ""
assert1 validate_package "not a package"
assert1 validate_package "../evil"
assert1 validate_package "com.evil/rm"
assert1 validate_package "singleword"
assert1 validate_package "123.bad.start"
assert1 validate_package "android"

echo "-- is_safe_path --"
assert0 is_safe_path com.a.b "/data/data/com.a.b/cache"
assert0 is_safe_path com.a.b "/data/user/0/com.a.b/cache"
assert0 is_safe_path com.a.b "/data/user/10/com.a.b/cache"
assert0 is_safe_path com.a.b "/data/user_de/0/com.a.b/cache"
assert1 is_safe_path com.a.b "/data/data/com.a.b/code_cache"
assert1 is_safe_path com.a.b "/data/data/com.a.b/files"
assert1 is_safe_path com.a.b "/data/data/com.a.b"
assert1 is_safe_path com.a.b "/data/data/other.pkg/cache"
assert1 is_safe_path com.a.b "/data/data/com.a.b/cache-evil"
assert1 is_safe_path com.a.b "/sdcard/Android/data/com.a.b/cache"
assert1 is_safe_path com.a.b "/"
assert1 is_safe_path com.a.b "/data"
assert1 is_safe_path com.a.b "/data/data/com.a.b/cache/../files"
assert1 is_safe_path "../evil" "/data/data/../evil/cache"

echo "-- clean_cache di FAKE_ROOT (destructive terisolasi) --"
FAKE="$(mktemp -d 2>/dev/null || echo /tmp/facc-test-$$)"
mkdir -p "$FAKE/data/data/com.test.app/cache/sub" 2>/dev/null
mkdir -p "$FAKE/data/data/com.test.app/files" 2>/dev/null
echo hello > "$FAKE/data/data/com.test.app/cache/f1.tmp" 2>/dev/null
echo hello > "$FAKE/data/data/com.test.app/cache/sub/f2.tmp" 2>/dev/null
echo hello > "$FAKE/data/data/com.test.app/files/keep.txt" 2>/dev/null
# Simulasi: override is_safe_path? Tidak — uji langsung rm pola ISI saja:
CACHE="$FAKE/data/data/com.test.app/cache"
rm -rf "$CACHE"/* 2>/dev/null
if [ ! -e "$CACHE/f1.tmp" ] && [ ! -e "$CACHE/sub" ] && [ -d "$CACHE" ] && [ -f "$FAKE/data/data/com.test.app/files/keep.txt" ]; then
  ok "hanya isi cache yang terhapus, files/ utuh, dir cache tetap ada"
else
  bad "pola hapus ISI cache bocor"
fi

echo "-- get_cache_size & human --"
mkdir -p "$FAKE/sz" && dd if=/dev/zero of="$FAKE/sz/f" bs=1024 count=100 2>/dev/null
SZ=$(get_cache_size "$FAKE/sz")
case "$SZ" in ''|*[!0-9]*) bad "get_cache_size bukan angka: $SZ" ;; *) ok "get_cache_size=$SZ" ;; esac
H=$(facc_human_size 190840832)
[ "$H" = "182.0 MB" ] && ok "human 182MB" || bad "human 182MB dapat $H"
H=$(facc_human_size 6442450944)
[ "$H" = "6.00 GB" ] && ok "human GB" || bad "human GB dapat $H"

echo "-- facc_validate_interval (5-1440 mnt, maks 24 jam) --"
[ "$(facc_validate_interval 30)" = "30" ] && ok "30 -> 30" || bad "30 ditolak"
[ "$(facc_validate_interval 5)" = "5" ] && ok "batas bawah 5" || bad "5 ditolak"
[ "$(facc_validate_interval 1440)" = "1440" ] && ok "batas atas 1440" || bad "1440 ditolak"
[ "$(facc_validate_interval 060)" = "60" ] && ok "060 -> 60 (nol depan)" || bad "060 gagal"
[ "$(facc_validate_interval 120)" = "120" ] && ok "120 (2 jam)" || bad "120 ditolak"
assert1 facc_validate_interval 4
assert1 facc_validate_interval 1441
assert1 facc_validate_interval 0
assert1 facc_validate_interval ""
assert1 facc_validate_interval abc
assert1 facc_validate_interval "60 mnt"
assert1 facc_validate_interval -30

echo "-- malformed config parser --"
echo 'AUTO_CLEAN=xx
INTERVAL_MINUTES=-5
GARBAGE((((' > "$FAKE/facc.conf"
v=$(grep -E "^AUTO_CLEAN=[01]" "$FAKE/facc.conf" 2>/dev/null | tail -n1 | cut -d= -f2)
[ -z "$v" ] && ok "malformed AUTO_CLEAN ditolak" || bad "malformed AUTO_CLEAN lolos: $v"

rm -rf "$FAKE" 2>/dev/null

echo ""
echo "Hasil: $PASS lulus, $FAIL gagal."
[ "$FAIL" -eq 0 ]
