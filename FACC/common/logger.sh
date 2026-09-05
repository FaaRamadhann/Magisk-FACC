#!/system/bin/sh
# FACC logger.sh
# Format: [TIME] [LEVEL] MESSAGE
# Target : /sdcard/facc-log/facc.log + latest.log + archive/
# Independen dari UI. Dipakai CLI, core, dan service.

# Resolve direktori log (prioritas: /sdcard -> /data/media/0 -> persist)
FACC_LOG_DIR="${FACC_LOG_DIR:-}"
if [ -z "$FACC_LOG_DIR" ]; then
  if [ -d "/sdcard" ]; then
    FACC_LOG_DIR="/sdcard/facc-log"
  elif [ -d "/data/media/0" ]; then
    FACC_LOG_DIR="/data/media/0/facc-log"
  else
    FACC_LOG_DIR="/data/adb/facc/log"
  fi
fi
FACC_LOG_FILE="$FACC_LOG_DIR/facc.log"
FACC_LATEST_LOG="$FACC_LOG_DIR/latest.log"
FACC_ARCHIVE_DIR="$FACC_LOG_DIR/archive"

FACC_MAX_LOG_SIZE_KB="${FACC_MAX_LOG_SIZE_KB:-512}"
FACC_MAX_ARCHIVE="${FACC_MAX_ARCHIVE:-7}"

_facc_ensure_logdir() {
  mkdir -p "$FACC_ARCHIVE_DIR" 2>/dev/null
  [ -f "$FACC_LOG_FILE" ] || : > "$FACC_LOG_FILE" 2>/dev/null
  [ -f "$FACC_LATEST_LOG" ] || : > "$FACC_LATEST_LOG" 2>/dev/null
}

_facc_timestamp() {
  date "+%H:%M:%S" 2>/dev/null || echo "??:??:??"
}

_facc_datestamp() {
  date "+%Y-%m-%d" 2>/dev/null || echo "unknown-date"
}

# Rotasi: jika facc.log > MAX, pindah ke archive/YYYY-MM-DD_HH-MM-SS.log
facc_rotate_if_needed() {
  _facc_ensure_logdir
  [ -f "$FACC_LOG_FILE" ] || return 0
  size_kb=$(du -k "$FACC_LOG_FILE" 2>/dev/null | awk '{print $1}')
  case "$size_kb" in ''|*[!0-9]*) return 0 ;; esac
  if [ "$size_kb" -ge "$FACC_MAX_LOG_SIZE_KB" ]; then
    ts=$(date "+%Y-%m-%d_%H-%M-%S" 2>/dev/null || echo "archive")
    mv "$FACC_LOG_FILE" "$FACC_ARCHIVE_DIR/${ts}.log" 2>/dev/null
    : > "$FACC_LOG_FILE" 2>/dev/null
    # Batasi jumlah arsip
    count=$(ls -1 "$FACC_ARCHIVE_DIR" 2>/dev/null | wc -l | tr -d ' ')
    if [ -n "$count" ] && [ "$count" -gt "$FACC_MAX_ARCHIVE" ]; then
      ls -1tr "$FACC_ARCHIVE_DIR" 2>/dev/null | head -n $((count - FACC_MAX_ARCHIVE)) | while read -r f; do
        rm -f "$FACC_ARCHIVE_DIR/$f" 2>/dev/null
      done
    fi
  fi
}

# facc_log LEVEL MESSAGE
# LEVEL: INFO SCAN CLEAN WARN ERROR
facc_log() {
  _level="${1:-INFO}"
  shift 2>/dev/null || shift
  _msg="$*"
  [ -z "$_msg" ] && _msg="$1"
  _facc_ensure_logdir
  facc_rotate_if_needed
  line="[$(_facc_timestamp)] [$_level] $_msg"
  echo "$line" >> "$FACC_LOG_FILE" 2>/dev/null
  echo "$line" >> "$FACC_LATEST_LOG" 2>/dev/null
  # Batasi latest.log 200 baris terakhir
  if [ -f "$FACC_LATEST_LOG" ]; then
    lines=$(wc -l < "$FACC_LATEST_LOG" 2>/dev/null | tr -d ' ')
    case "$lines" in ''|*[!0-9]*) ;; *)
      if [ "$lines" -gt 200 ]; then
        tail -n 200 "$FACC_LATEST_LOG" > "$FACC_LATEST_LOG.tmp" 2>/dev/null && \
          mv "$FACC_LATEST_LOG.tmp" "$FACC_LATEST_LOG" 2>/dev/null
      fi
    esac
  fi
  # Echo ke stdout hanya untuk WARN/ERROR agar tidak mengotori output --json
  case "$_level" in
    WARN|ERROR) echo "$line" >&2 ;;
  esac
}

facc_log_info()  { facc_log "INFO" "$*"; }
facc_log_scan()  { facc_log "SCAN" "$*"; }
facc_log_clean() { facc_log "CLEAN" "$*"; }
facc_log_warn()  { facc_log "WARN" "$*"; }
facc_log_error() { facc_log "ERROR" "$*"; }
