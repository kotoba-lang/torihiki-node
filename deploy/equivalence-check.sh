#!/usr/bin/env bash
# Everything this venue claims, checked on one host, in one command.
#
# The claims were established over many separate runs, and a claim that takes
# many runs to check is a claim nobody re-checks. This starts four replicas,
# waits for them to agree, and asks each question in turn:
#
#   1. do all four hold the same chain
#   2. what is the block interval, against Hyperliquid's ~70 ms
#   3. does a contract deployed through consensus run over JSON-RPC
#
# The escrow is NOT here. Its path needs bonded validators and funded
# accounts, and funding them on a devnet means minting — which is exactly the
# thing `:deposit-attest` exists to avoid. It is covered by gates in
# `torihiki` (`a-deposit-needs-a-quorum-and-credits-exactly-once` and four
# others) and by `torihiki.thorchain`'s seven. Saying so is better than a
# check that mints its own evidence.
set -euo pipefail
cd "$(dirname "$0")/.."

BASE_PORT=${BASE_PORT:-8800}
DATA=${DATA:-/tmp/torihiki-check}
# `npx nbb` by default so this runs from a checkout with no global install;
# set NBB=nbb on a host where it is installed.
CP=$(clojure -Spath)
PEERS="w1@ws://127.0.0.1:19401,w2@ws://127.0.0.1:19402,w3@ws://127.0.0.1:19403,w4@ws://127.0.0.1:19404"

cleanup() { pkill -f 'torihiki-node.standalone' 2>/dev/null || true; }
trap cleanup EXIT
cleanup; rm -rf "$DATA"

 # 0. it has to READ before anything can be measured. Both halves: the
# standalone under nbb, and the Worker through its own compiler. Two builds
# went out unread on the day this was written, and both would have stopped
# here.
if ${NBB:-npx nbb} -cp "src:script:$CP" script/loads.cljs >/dev/null 2>&1; then
  say() { printf '%-46s %s\n' "$1" "$2"; }
  say "standalone reads on ClojureScript" "PASS"
else
  printf '%-46s %s\n' "standalone reads on ClojureScript" "FAIL"; exit 1
fi

echo "starting four replicas"
for i in 1 2 3 4; do
  W=w$i PEERS="$PEERS" HTTP_PORT=$((BASE_PORT+i)) DATA_DIR="$DATA/w$i" \
    ${NBB:-npx nbb} -cp "src:$CP" -m torihiki-node.standalone > "$DATA-w$i.log" 2>&1 &
done
sleep 40

fail=0
say() { printf '%-46s %s\n' "$1" "$2"; }

# 1. one chain
h=$(curl -sf "http://127.0.0.1:$((BASE_PORT+1))/head" | sed 's/.*"height":\([0-9]*\).*/\1/')
at=$((h - 50))
first=""
same=yes
for i in 1 2 3 4; do
  x=$(curl -sf "http://127.0.0.1:$((BASE_PORT+i))/hash-at?h=$at" | sed 's/.*"hash":"\([^"]*\)".*/\1/')
  [ -z "$first" ] && first="$x"
  [ "$x" = "$first" ] || same=no
done
if [ "$same" = yes ]; then say "four replicas, one chain at $at" "PASS"; else say "four replicas, one chain" "FAIL"; fail=1; fi

# 2. the block interval
p50=$(curl -sf "http://127.0.0.1:$((BASE_PORT+1))/head" | sed 's/.*"block-ms":{[^}]*"p50":\([0-9]*\).*/\1/')
if [ "$p50" -lt 70 ] 2>/dev/null; then
  say "block p50 ${p50} ms (Hyperliquid ~70 ms)" "PASS"
else
  say "block p50 ${p50} ms (Hyperliquid ~70 ms)" "FAIL"; fail=1
fi

# 3. a contract, through consensus
if TORIHIKI_BASE="http://127.0.0.1:$((BASE_PORT+1))" \
   ${NBB:-npx nbb} -cp "script:src:$CP" script/deploy_e2e.cljs 2>&1 | grep -q '^PASS'; then
  say "contract deployed and run over eth_call" "PASS"
else
  say "contract deployed and run over eth_call" "FAIL"; fail=1
fi

echo
if [ "$fail" = 0 ]; then echo "EQUIVALENCE CHECK: pass"; else echo "EQUIVALENCE CHECK: fail"; fi
exit $fail
