#!/system/bin/sh
set -eu
KEY=${1:?private keystore path required}
exec sh "$(dirname "$0")/../build.sh" "$KEY"
