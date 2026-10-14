#!/system/bin/sh
set -eu
umask 077
ROOT=/data/local/tmp/meshell
mkdir -p "$ROOT/home/bin" "$ROOT/home/tmp" /sdcard/MeShell
chmod 700 "$ROOT" "$ROOT/home" "$ROOT/home/bin" "$ROOT/home/tmp"
if [ -f "$ROOT/broker.pid" ]; then
    pid=$(cat "$ROOT/broker.pid")
    case "$pid" in ''|*[!0-9]*) echo 'Invalid broker PID'; exit 1;; esac
    exe=$(readlink "/proc/$pid/exe" 2>/dev/null || true)
    case "$exe" in "$ROOT/meshelld"|"$ROOT/meshelld (deleted)")
        kill "$pid"
        for n in 1 2 3 4 5; do
            kill -0 "$pid" 2>/dev/null || break
            sleep 1
        done
        ;;
    esac
fi
token=''
for attempt in 1 2 3 4 5 6 7 8 9 10; do
    reply=$(content call --user 0 --uri content://com.mesystem.shell.bootstrap --method bootstrap 2>/dev/null || true)
    token=$(printf '%s' "$reply" | sed -n 's/.*token=\([0-9a-f]\{64\}\).*/\1/p')
    [ "${#token}" -eq 64 ] && break
    sleep 0.5
done
[ "${#token}" -eq 64 ] || { echo 'App bootstrap failed'; exit 1; }
printf '%s' "$token" > "$ROOT/token"
chmod 600 "$ROOT/token"
unset token reply
"$ROOT/meshelld" --daemon
echo 'MeShell: local shell broker started on 127.0.0.1:38765'
