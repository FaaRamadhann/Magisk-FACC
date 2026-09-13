#!/system/bin/sh
# FACC uninstall.sh : dipanggil Magisk/KernelSU saat module dihapus.
# Hanya bersihkan jejak FACC. JANGAN sentuh data aplikasi lain.

# Hentikan service yang mungkin jalan
pkill -f "facc.*daemon" 2>/dev/null
pkill -f "service.sh.*facc" 2>/dev/null

# Hapus symlink / binary yang mungkin dibuat manual (bukan overlay)
rm -f /data/adb/service.d/facc_service.sh 2>/dev/null

# NOTE: sengaja TIDAK menghapus /sdcard/facc-log dan /data/adb/facc
# agar log & config user tetap ada sebagai arsip.
# Hapus manual jika mau bersih total:
#   rm -rf /sdcard/facc-log /data/adb/facc

# NOTE: FACC Manager (com.faa.facc) sengaja TIDAK di-uninstall otomatis
# agar aplikasi tetap bisa dipakai setelah module dihapus.
# Hapus manual bila perlu: pm uninstall com.faa.facc

exit 0
