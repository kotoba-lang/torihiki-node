#!/bin/sh
# Is this validator part of a chain that is moving, and is it on the same one?
#
# Two questions, because they fail apart: a replica can be perfectly healthy
# and alone, and it can be in touch with everybody and stuck. Anything that
# answers only one of them will call one of those states fine.
set -eu
BASE="${1:-http://127.0.0.1:8801}"
PEERS="${2:-}"

h1=$(curl -sf --max-time 5 "$BASE/head" | sed 's/.*"height":\([0-9]*\).*/\1/')
sleep 3
h2=$(curl -sf --max-time 5 "$BASE/head" | sed 's/.*"height":\([0-9]*\).*/\1/')

if [ "$h1" = "$h2" ]; then
  echo "STUCK at $h1"
  exit 1
fi

# On the same chain as the peers: same block hash at a height everyone passed.
if [ -n "$PEERS" ]; then
  at=$((h1 - 20))
  mine=$(curl -sf --max-time 5 "$BASE/hash-at?h=$at" | sed 's/.*"hash":"\([^"]*\)".*/\1/')
  for p in $PEERS; do
    theirs=$(curl -sf --max-time 5 "$p/hash-at?h=$at" | sed 's/.*"hash":"\([^"]*\)".*/\1/')
    if [ -n "$theirs" ] && [ "$theirs" != "$mine" ]; then
      echo "FORKED from $p at height $at"
      exit 2
    fi
  done
fi

echo "OK moving $h1 -> $h2"
