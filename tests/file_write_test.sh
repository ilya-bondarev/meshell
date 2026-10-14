#!/system/bin/sh
set -eu
D=$(mktemp -d "${TMPDIR:-/tmp}/file-write-test.XXXXXXXX")
trap 'rm -rf "$D"' EXIT
printf old > "$D/expected"
printf old > "$D/target"
printf new > "$D/next"
exec 9>"$D/lock"
flock -x 9 9>&9
cmp -s "$D/expected" "$D/target"
mv "$D/next" "$D/target"
printf other > "$D/target"
printf new > "$D/next"
if cmp -s "$D/expected" "$D/target"; then echo 'Conflict incorrectly accepted'; exit 1; fi
[ "$(cat "$D/target")" = other ]
# mksh closes descriptor 9 on exec unless explicitly passed to flock.
if sh -c 'exec 9>"$1"; flock -n 9 9>&9' sh "$D/lock"; then echo 'Lock exclusion failed'; exit 1; fi
echo FILE_WRITE_TEST_OK
