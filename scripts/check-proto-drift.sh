#!/bin/sh
# Fail if this service's copy of auth.proto differs from auth-service's.
# Same role as chat-service/scripts/check-proto-drift.sh. Run before every build that ships (Step 54 runs it too).
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SRC="$ROOT/auth-service/proto/auth.proto"
DST="$ROOT/messages-websocket-service/src/main/proto/auth.proto"
if cmp -s "$SRC" "$DST"; then
  echo "proto-drift: OK (sha256 $(sha256sum "$DST" | cut -c1-12))"
  exit 0
fi
echo "proto-drift: DRIFT - src/main/proto/auth.proto differs from auth-service/proto/auth.proto" >&2
diff -u "$DST" "$SRC" >&2 || true
echo "proto-drift: run messages-websocket-service/scripts/sync-proto.sh, rebuild, and re-run Step 23" >&2
exit 1
