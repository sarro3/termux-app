#!/system/bin/sh
HERE="$(cd "$(dirname "$0")" && pwd)"
PRELOAD="$HERE/libtermux-prefix-remap.so"
if [ -f "$PRELOAD" ]; then
  export LD_PRELOAD="$PRELOAD${LD_PRELOAD:+:$LD_PRELOAD}"
fi
export TERMUX_PREFIX_REMAP_FROM="${TERMUX_PREFIX_REMAP_FROM:-/data/data/com.termux}"
UID_USER=$(($(awk '/^Uid:/{print $2; exit}' /proc/self/status) / 100000))
if [ "$UID_USER" -eq 0 ]; then
  export TERMUX_PREFIX_REMAP_TO="${TERMUX_PREFIX_REMAP_TO:-/data/data/tx.work}"
else
  export TERMUX_PREFIX_REMAP_TO="${TERMUX_PREFIX_REMAP_TO:-/data/user/${UID_USER}/tx.work}"
fi
exec "$@"
