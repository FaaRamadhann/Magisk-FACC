#!/system/bin/sh
# FACC core.sh — Cache Cleanup Engine
# Prinsip SAFE:
#   - Never pm clear
#   - Never delete app data (hanya isi direktori cache/)
#   - Validate target path (harus milik package yang dimaksud)
#   - Fail-safe (gagal validasi = ABORT, tanpa fallback rm -rf)
#
# Flow: Package -> Find cache -> Validate path -> Check ownership ->
#       Calculate size -> Delete contents -> Verify -> Write log

# Guard double-source
[ -n "$__FACC_CORE_LOADED" ] && return 0 2>/dev/null
__FACC_CORE_LOADED=1

# Lokasi script (agar bisa resolve logger saat standalone)
_FACC_SCRIPT_DIR="$(dirname "$0" 2>/dev/null)"
case "$_FACC_SCRIPT_DIR" in
  "") _FACC_SCRIPT_DIR="." ;;
esac
# Cari logger.sh di beberapa lokasi umum
for _d in "$_FACC_SCRIPT_DIR" "$_FACC_SCRIPT_DIR/../common" "/data/adb/modules/facc/common" "/data/adb/modules_update/facc/common"; do
  if [ -f "$_d/logger.sh" ] && [ -z "$__FACC_LOGGER_LOADED" ]; then
    # shellcheck source=/dev/null
    . "$_d/logger.sh" 2>/dev/null && __FACC_LOGGER_LOADED=1 && break
  fi
done
unset _d
# Fallback no-op logger bila file tidak ketemu (misal saat unit test)
if [ -z "$__FACC_LOGGER_LOADED" ]; then
  facc_log() { echo "[$1] $2" >&2; }
  facc_log_info() { :; }; facc_log_scan() { :; }
  facc_log_clean() { :; }; facc_log_warn() { echo "WARN: $*" >&2; }
  facc_log_error() { echo "ERROR: $*" >&2; }
fi

FACC_VERSION="${FACC_VERSION:-1.0.5}"

# Direktori data yang dipindai per user. Owner 0 ada di /data/data.
# Multi-user lain di /data/user/<id>/.
FACC_DATA_ROOTS_DEFAULT="/data/data /data/user/0"

# ---------------------------------------------------------------
# Environment detection
# ---------------------------------------------------------------
facc_detect_android() {
  FACC_SDK="$(getprop ro.build.version.sdk 2>/dev/null)"
  FACC_RELEASE="$(getprop ro.build.version.release 2>/dev/null)"
  [ -z "$FACC_SDK" ] && FACC_SDK="unknown"
  [ -z "$FACC_RELEASE" ] && FACC_RELEASE="unknown"
  echo "$FACC_SDK"
}

facc_is_root() {
  [ "$(id -u 2>/dev/null)" = "0" ]
}

facc_require_root() {
  if ! facc_is_root; then
    echo "FACC butuh root. Jalankan: su -c facc" >&2
    facc_log_error "Root check gagal (uid=$(id -u 2>/dev/null))"
    return 1
  fi
  return 0
}

# ---------------------------------------------------------------
# validate_package(pkg) -> 0 valid, 1 invalid
# Aturan: format java-package + (opsional, jika pm tersedia) package terinstal.
# ---------------------------------------------------------------
validate_package() {
  _pkg="$1"
  [ -z "$_pkg" ] && return 1
  # Tolak karakter berbahaya / path traversal
  case "$_pkg" in
    *"/"*|*" "*|*";"*|*"&"*|*"|"*|*"\$"*|*"\`"*|*"*"*|*"!"*|*"#"*) return 1 ;;
  esac
  # Format: huruf/angka/underscore dipisah titik, min 1 titik, tiap segmen tidak diawali angka
  echo "$_pkg" | grep -Eq '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$' || return 1
  # Blokir prefix sistem berbahaya yang tidak boleh dibersihkan sembarangan
  case "$_pkg" in
    android|com.android.systemui|com.android.phone) return 1 ;;
  esac
  return 0
}

# Cek apakah package terinstal (best-effort, tidak fatal bila pm tak ada)
facc_package_installed() {
  _pkg="$1"
  # 1) direktori data ada?
  [ -d "/data/data/$_pkg" ] && return 0
  for _u in /data/user/*; do
    [ -d "$_u/$_pkg" ] && return 0
  done
  # 2) pm path (butuh root/shell)
  if command -v pm >/dev/null 2>&1; then
    pm path "$_pkg" 2>/dev/null | grep -q "^package:" && return 0
  fi
  return 1
  unset _u
}

# ---------------------------------------------------------------
# is_safe_path(pkg, path) -> 0 aman, 1 tidak aman
# Syarat aman:
#  - path absolut, diawali /data/
#  - path akhir tepat ".../<pkg>/cache" (bukan code_cache, bukan files)
#  - tidak mengandung .. atau link keluar (cek readlink bila ada)
#  - tidak masuk daftar kritis
# ---------------------------------------------------------------
is_safe_path() {
  _pkg="$1"
  _path="$2"
  [ -z "$_pkg" ] || [ -z "$_path" ] && return 1
  validate_package "$_pkg" || return 1

  # Harus absolut
  case "$_path" in /*) ;; *) return 1 ;; esac
  # Tolak traversal & karakter aneh
  case "$_path" in *"../"*|*"/.."*|*"*"*|*" "*|*";"*|*"&"*|*"|"*) return 1 ;; esac

  # Canonicalize bila readlink tersedia
  if command -v readlink >/dev/null 2>&1; then
    _canon=$(readlink -f "$_path" 2>/dev/null)
    [ -n "$_canon" ] && _path="$_canon"
    unset _canon
  fi

  # Daftar path yang TIDAK BOLEH disentuh
  case "$_path" in
    "/"|"/data"|"/data/"|"/data/data"|"/data/data/"|"/data/user"|"/data/user/"| \
    "/sdcard"|"/sdcard/"|"/storage"|"/system"|"/vendor"|"/apex") return 1 ;;
  esac

  # Pola yang diizinkan (ecxact match):
  #   /data/data/<pkg>/cache
  #   /data/user/<N>/<pkg>/cache
  #   /data/user_de/<N>/<pkg>/cache
  _ok=1
  [ "$_path" = "/data/data/$_pkg/cache" ] && _ok=0
  # shellcheck disable=SC2254
  case "$_path" in
    /data/user/[0-9]*/"$_pkg"/cache|/data/user_de/[0-9]*/"$_pkg"/cache) _ok=0 ;;
  esac
  [ "$_ok" = "0" ] && return 0
  return 1
}

# validate_cache_path(pkg, path): wrapper + pastikan direktori ada & memang direktori
validate_cache_path() {
  _pkg="$1"
  _path="$2"
  is_safe_path "$_pkg" "$_path" || {
    echo "CLEANUP ABORTED - Reason: unsafe target ($_path)" >&2
    return 1
  }
  [ -e "$_path" ] || return 2
  [ -d "$_path" ] || return 1
  return 0
}

# ---------------------------------------------------------------
# facc_find_cache_dirs(pkg) : cetak daftar direktori cache milik pkg (satu per baris)
# ---------------------------------------------------------------
facc_find_cache_dirs() {
  _pkg="$1"
  validate_package "$_pkg" || return 1
  _found=0; _seen=""
  for _root in /data/data "/data/user"/* /data/user_de/*; do
    case "$_root" in
      "/data/user/*"|"/data/user_de/*") continue ;;
    esac
    _c=""
    if [ "$_root" = "/data/data" ]; then
      _c="/data/data/$_pkg/cache"
    else
      _c="$_root/$_pkg/cache"
    fi
    # Pastikan hasil tetap lolos safety sebelum dicetak
    if is_safe_path "$_pkg" "$_c" && [ -d "$_c" ]; then
      # Dedupe: /data/data/<pkg>/cache dan /data/user/0/<pkg>/cache
      # sering menunjuk direktori fisik yang sama (symlink/bind).
      # Tanpa ini tiap app dibersihkan 2-3x dan log spam.
      _canon="$_c"
      if command -v readlink >/dev/null 2>&1; then
        _r=$(readlink -f "$_c" 2>/dev/null)
        [ -n "$_r" ] && _canon="$_r"
      fi
      case "$_seen" in
        *"$_canon"*) continue ;;
      esac
      _seen="$_seen
$_canon"
      echo "$_c"
      _found=$((_found + 1))
    fi
  done
  unset _root _c _canon _r
  [ "$_found" -gt 0 ]
}

# ---------------------------------------------------------------
# get_cache_size(path) : cetak ukuran bytes (angka). Return 1 bila gagal.
# ---------------------------------------------------------------
get_cache_size() {
  _p="$1"
  [ -d "$_p" ] || { echo 0; return 1; }
  # Direktori kosong = 0. Tanpa ini, du menghitung overhead metadata
  # dir (~12 KB) sehingga scan melaporkan "cache" padahal sudah bersih.
  if [ -z "$(ls -A "$_p" 2>/dev/null)" ]; then
    echo 0
    return 0
  fi
  _bytes=$(du -sk "$_p" 2>/dev/null | awk '{print $1}')
  case "$_bytes" in ''|*[!0-9]*) echo 0; return 1 ;; esac
  echo $((_bytes * 1024))
  unset _bytes
}

# Human readable: 182 MB dst.
# NOTE: perbandingan via awk, BUKAN [ -ge ]. mksh di Android memakai
# aritmetika 32-bit, nilai >2 GB overflow sehingga cabang GB tidak
# pernah tercapai (terbukti di test: 6442450944 dibaca sebagai B).
facc_human_size() {
  _b="$1"
  case "$_b" in ''|*[!0-9]*) _b=0 ;; esac
  awk -v b="$_b" 'BEGIN {
    if (b >= 1073741824) printf "%.2f GB", b/1073741824;
    else if (b >= 1048576) printf "%.1f MB", b/1048576;
    else if (b >= 1024) printf "%.0f KB", b/1024;
    else printf "%d B", b;
  }'
}

# ---------------------------------------------------------------
# facc_validate_interval(menit) : validasi durasi auto-clean.
# Cetak nilai ternormalisasi (tanpa nol depan), return 1 bila invalid.
# Batas: 5 - 1440 menit (1440 = 24 jam). Murni string/digit, aman di mksh
# 32-bit (nilai kecil) dan tahan input oktal ("08" -> 8, bukan error).
# ---------------------------------------------------------------
facc_validate_interval() {
  _in="$1"
  case "$_in" in ''|*[!0-9]*) return 1 ;; esac
  _in=$(echo "$_in" | sed 's/^0*//')
  [ -z "$_in" ] && _in="0"
  [ "$_in" -ge 5 ] 2>/dev/null || return 1
  [ "$_in" -le 1440 ] 2>/dev/null || return 1
  echo "$_in"
  unset _in
}

# ---------------------------------------------------------------
# clean_cache(pkg, [cache_dir]) : hapus ISI cache saja, pertahankan direktorinya.
# Return: 0 sukses, 1 abort (unsafe), 2 dir tidak ada, 3 gagal hapus.
# ---------------------------------------------------------------
clean_cache() {
  _pkg="$1"
  _dir="$2"

  validate_package "$_pkg" || {
    echo "CLEANUP ABORTED - Reason: invalid package ($_pkg)" >&2
    facc_log_error "Invalid package: $_pkg"
    return 1
  }

  # Jika dir tidak disebut, cari semua cache milik pkg
  if [ -z "$_dir" ]; then
    _list=$(facc_find_cache_dirs "$_pkg")
    if [ -z "$_list" ]; then
      return 2
    fi
    _rc=0; _total_freed=0
    while read -r _d; do
      [ -z "$_d" ] && continue
      _out=$(clean_cache "$_pkg" "$_d" 2>/dev/null)
      _st=$?
      if [ "$_st" -ne 0 ]; then
        _rc="$_st"
      else
        case "$_out" in ''|*[!0-9]*) ;; *) _total_freed=$((_total_freed + _out)) ;; esac
      fi
    done <<EOF_LIST
$_list
EOF_LIST
    echo "$_total_freed"
    return "$_rc"
  fi

  validate_cache_path "$_pkg" "$_dir" || {
    echo "❌ CLEANUP ABORTED" >&2
    echo "Reason: unsafe target" >&2
    facc_log_error "ABORTED unsafe target pkg=$_pkg path=$_dir"
    return 1
  }

  _before=$(get_cache_size "$_dir")
  # Hapus ISI saja — pola aman: "$_dir"/.. tidak pernah dipakai.
  # Termasuk file tersembunyi di dalam cache.
  rm -rf "$_dir"/* 2>/dev/null
  # Hapus dotfiles (tapi lindungi . dan .. secara eksplisit)
  for _dot in "$_dir"/.[!.]* "$_dir"/..?*; do
    case "$_dot" in
      "$_dir/."|"$_dir/..") continue ;;
    esac
    [ -e "$_dot" ] || [ -L "$_dot" ] || continue
    # Safety: dotfile harus tetap di dalam cache dir
    case "$_dot" in "$_dir"/*) rm -rf "$_dot" 2>/dev/null ;; esac
  done
  unset _dot

  verify_cleanup "$_pkg" "$_dir" || {
    facc_log_error "Verify gagal pkg=$_pkg path=$_dir"
    return 3
  }

  _after=$(get_cache_size "$_dir")
  _freed=$((_before - _after))
  [ "$_freed" -lt 0 ] && _freed=0
  facc_log_clean "$_pkg - $(facc_human_size "$_freed")"
  echo "$_freed"
  unset _pkg _dir _before _after _freed
  return 0
}

# ---------------------------------------------------------------
# verify_cleanup(pkg, dir): pastikan dir masih ada & isinya ~kosong.
# Toleransi: <= 64KB dianggap bersih (ada file yang langsung dibuat ulang OS).
# ---------------------------------------------------------------
verify_cleanup() {
  _pkg="$1"
  _dir="$2"
  is_safe_path "$_pkg" "$_dir" || return 1
  [ -d "$_dir" ] || return 1
  _left=$(get_cache_size "$_dir")
  case "$_left" in ''|*[!0-9]*) return 1 ;; esac
  [ "$_left" -le 65536 ]
}

# ---------------------------------------------------------------
# facc_scan_all([user_id]) : scan semua package, output "pkg|bytes|dir"
# Dipakai CLI --scan dan WebUI. Tidak menghapus apa pun.
# ---------------------------------------------------------------
facc_list_packages() {
  if command -v pm >/dev/null 2>&1; then
    pm list packages 2>/dev/null | sed 's/^package://' | sort -u
  else
    # Fallback: daftar direktori /data/data
    for _d in /data/data/*; do
      [ -d "$_d" ] || continue
      basename "$_d"
    done
  fi
  unset _d
}

facc_scan_all() {
  for _pkg in $(facc_list_packages); do
    validate_package "$_pkg" || continue
    _total=0; _dirs=""
    for _c in $(facc_find_cache_dirs "$_pkg" 2>/dev/null); do
      _s=$(get_cache_size "$_c" 2>/dev/null)
      case "$_s" in ''|*[!0-9]*) _s=0 ;; esac
      _total=$((_total + _s))
      _dirs="${_dirs:+$_dirs,}$_c"
    done
    # Cetak semua, termasuk yang 0 agar WebUI bisa tampilkan status lengkap.
    # Format pipe agar mudah diparse: pkg|bytes|dir1,dir2
    echo "$_pkg|$_total|$_dirs"
  done
  unset _pkg _total _dirs _c _s
}
