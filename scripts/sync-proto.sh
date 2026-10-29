#!/bin/sh
# Copy auth-service's proto (the single source of truth) into this service.
# Same role as chat-service/scripts/sync-proto.sh. Never edit src/main/proto/auth.proto by hand.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SRC="$ROOT/auth-service/proto/auth.proto"
DST="$ROOT/messages-websocket-service/src/main/proto/auth.proto"
[ -f "$SRC" ] || { echo "sync-proto: source not found: $SRC" >&2; exit 1; }
mkdir -p "$(dirname "$DST")"
cp "$SRC" "$DST"
echo "sync-proto: copied auth-service/proto/auth.proto (sha256 $(sha256sum "$DST" | cut -c1-12))"
