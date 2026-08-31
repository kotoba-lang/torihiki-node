#!/bin/sh
# Place one validator on one fleet host, as a launchd resident.
#
#   deploy/install-fleet.sh <host> <witness> <chain-id> <witnesses> <peers> [pubs-env-file]
#
# ## Why this exists rather than `torihiki@.service`
#
# That unit is systemd and the murakumo fleet is macOS mac-minis, so it has
# never been runnable on the hosts it was written for.
#
# ## The seed is generated ON the host and never leaves it
#
# `SEED_<w>` is this validator's identity. Generating it here and copying it
# out would put every validator's secret on one laptop, which makes a
# four-replica set worth exactly one compromise. `openssl rand` runs over ssh,
# the private half lands in a 0600 file the launchd job reads, and it is never
# read back. The PUBLIC half is printed, because the genesis set needs it and
# it is not a secret.
#
# Run once per host to collect the four public keys, then once more with all
# four in `<pubs-env-file>` so every host can verify every peer. Until that
# second pass the set is incomplete and `/head` reports `bft: none` rather
# than pretending -- see `key-provenance`.
#
# ## The dependencies travel with it
#
# nbb needs the source of every git dependency, and a fleet node has no
# sibling checkouts. `script/nbb-classpath.cljs` materialises them into
# `.nbb-deps/` from the deps.edn pins; that directory is what ships, so the
# host runs the pinned code rather than whatever it could reach.
set -eu
HOST="$1"; W="$2"; CHAIN="$3"; WITS="$4"; PEERS="$5"; PUBS_FILE="${6:-}"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
LABEL="cloud.murakumo.torihiki.$W"
UW=$(echo "$W" | tr a-z A-Z)
PORT_L="${LISTEN_PORT:-19401}"; PORT_H="${HTTP_PORT:-8801}"

test -d "$REPO/.nbb-deps" || { echo "no .nbb-deps -- run: nbb script/nbb-classpath.cljs" >&2; exit 2; }

echo "== $HOST: staging source and pinned dependencies"
ssh "$HOST" 'mkdir -p ~/.torihiki/data ~/Library/LaunchAgents'
rsync -a --delete "$REPO/src/" "$HOST:.torihiki/src/"
rsync -a --delete "$REPO/.nbb-deps/" "$HOST:.torihiki/.nbb-deps/"
# The npm half. `.nbb-deps` carries the Clojure sources of the git pins and
# nothing else -- `ws` for the peer sockets and `@noble/hashes` for the CID,
# both required at runtime on :cljs, come from package.json. Shipping the
# sources without these gets `Cannot find module 'ws'` at startup, which is
# what the first fleet placement did.
rsync -a "$REPO/package.json" "$REPO/package-lock.json" "$HOST:.torihiki/"
ssh "$HOST" 'cd ~/.torihiki && npm install --omit=dev --silent >/dev/null 2>&1 || npm install --silent'


echo "== $HOST: seed (generated there, stays there)"
ssh "$HOST" 'test -f ~/.torihiki/seed || { umask 077; openssl rand -hex 32 > ~/.torihiki/seed; }'
PUB=$(ssh "$HOST" 'node -e "
const nc=require(\"node:crypto\"),fs=require(\"fs\");
const seed=Buffer.from(fs.readFileSync(process.env.HOME+\"/.torihiki/seed\",\"utf8\").trim(),\"hex\");
const pre=Buffer.from(\"302e020100300506032b657004220420\",\"hex\");
const sk=nc.createPrivateKey({key:Buffer.concat([pre,seed]),format:\"der\",type:\"pkcs8\"});
console.log(nc.createPublicKey(sk).export({format:\"der\",type:\"spki\"}).toString(\"base64\"));"')


echo "== $HOST: runner"
ssh "$HOST" "cat > ~/.torihiki/run.sh" <<RUN
#!/bin/sh
set -eu
cd "\$HOME/.torihiki"
CP="src"
for d in .nbb-deps/*/src; do CP="\$CP:\$d"; done
export SEED_$UW="\$(cat "\$HOME/.torihiki/seed")"
exec npx --yes nbb --classpath "\$CP" -m torihiki-node.standalone
RUN
ssh "$HOST" 'chmod +x ~/.torihiki/run.sh'

echo "== $HOST: launchd"
HOME_REMOTE=$(ssh "$HOST" 'echo $HOME')
# Rendered by python rather than sed+awk: `awk -v` cannot carry a multi-line
# value, and the version that tried produced a ZERO-BYTE plist that
# `launchctl load` accepted in silence. A job that never starts and an empty
# file that loads without complaint look identical from here.
PLIST=$(python3 -c '
import sys
tpl, pubs_file = sys.argv[1], sys.argv[2]
subs = dict(zip(["__WITNESS__","__HOME__","__CHAIN_ID__","__WITNESSES__",
                 "__PEERS__","__LISTEN_PORT__","__HTTP_PORT__"], sys.argv[3:10]))
pubs = ""
if pubs_file:
    for line in open(pubs_file):
        line = line.strip()
        if not line or "=" not in line: continue
        k, v = line.split("=", 1)
        pubs += "    <key>%s</key><string>%s</string>\n" % (k, v)
out = []
for line in open(tpl).read().split("\n"):
    if line == "__PUBS__":
        out.append(pubs.rstrip("\n"))
        continue
    for k, v in subs.items(): line = line.replace(k, v)
    out.append(line)
sys.stdout.write("\n".join(out))
' "$REPO/deploy/launchd/cloud.murakumo.torihiki.plist.template" "$PUBS_FILE" \
  "$W" "$HOME_REMOTE" "$CHAIN" "$WITS" "$PEERS" "$PORT_L" "$PORT_H")

# A plist that rendered to nothing must not be installed.
case "$PLIST" in
  *"</plist>"*) : ;;
  *) echo "plist rendered empty or truncated -- refusing to install" >&2; exit 2 ;;
esac
printf '%s\n' "$PLIST" | ssh "$HOST" "cat > ~/Library/LaunchAgents/$LABEL.plist"

ssh "$HOST" "launchctl unload ~/Library/LaunchAgents/$LABEL.plist 2>/dev/null || true; \
             launchctl load ~/Library/LaunchAgents/$LABEL.plist"
echo "PUB_$UW=$PUB"
