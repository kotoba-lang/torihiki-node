# torihiki-node

The transport for [`torihiki`](https://github.com/kotoba-lang/torihiki): a
Cloudflare Worker in front of a Durable Object sequencer.

**Live:** `https://torihiki-node.04-feasts-minded.workers.dev`

```
GET  /head                    height, state root, running code version
GET  /market?id=1             risk parameters, mark, oracle, funding
GET  /book?market=1&depth=15  order book snapshot
GET  /account?id=<n>          positions, equity, margin, free collateral
POST /tx                      a signed transaction envelope
```

## Why a Durable Object

`torihiki.log` needs exactly one writer. Cloudflare guarantees one instance
per id, single-threaded — so "there is exactly one writer" comes from the
platform instead of being implemented with a write lease and a fencing epoch,
which is the kind of thing that looks right in a design document and loses
money in production.

This is a **sequencer, not consensus**. One writer decides the order; nothing
votes. `/head` says so in its own response.

## Why the engine, not a reimplementation

`/tx` runs `torihiki.state/apply-block` — the same compiled `.cljc` a JVM
validator runs. The advanced-optimised bundle was checked against the JVM and
produces identical state roots, so a client can replay this node's log and
contradict it. A hand-written JavaScript order book here would make that
impossible and would be a second implementation to keep in agreement forever.

## Authentication

Ed25519 via WebCrypto. This is the only place in the system that knows what
Ed25519 is — `torihiki.auth` takes verification as a parameter so the engine
stays runnable where there is no crypto to import.

Verified live: a replayed nonce is refused (`bad-nonce`), and signing someone
else's account with your own key is refused (`wrong-key`).

## Durability, and its limit

Every authenticated transaction is appended to Durable Object storage and
replayed on a cold start. Rejected-but-authenticated transactions are logged
too — they spent a nonce, and a replay that skipped them would leave the node
disagreeing with itself across an eviction, making the signature replayable by
waiting.

The exchange is never serialised: replaying the log **is** the state, so there
is no second encoding to keep in sync. The cost is a cold start proportional
to the log, and the honest limit is that a long-lived node needs snapshotting
and does not have it.

## A Durable Object does not pick up a deploy

It keeps executing the code it started with until it is evicted. For a
sequencer holding state in memory that can last indefinitely under traffic, so
"deployed" and "running" are different facts. `code-version` in `/head` exists
because this was learned twice in one session: a fix was deployed, verified
present in the bundle, and then contradicted by the live endpoint still
running the previous build. **Check `/head` before believing a deploy.**

## End-to-end latency, on Hyperliquid's axis

Hyperliquid publishes one latency figure for itself: from a co-located client,
send → committed response, median 0.2 s and p99 0.9 s. `script/latency_probe.cljk`
measures the same interval here — a signed post-only order, far from the touch,
timed until `/account` shows its nonce consumed — and prints the `/head` round
trip beside it, because this client is not co-located with anything. It
refuses any chain whose id does not say devnet, and cleans its orders up with a
cancel-all.

```bash
kbb --backend sci --classpath "$(kbb --backend sci script/nbb-classpath.cljk)" \
    script/latency_probe.cljk 20                       # validator-v3 (default)
TORIHIKI_BASE=https://torihiki-node.04-feasts-minded.workers.dev …   # the sequencer
```

Exit 2 means UNMEASURED — nothing committed — which is a statement about the
chain, not a latency. That is what both deployments gave on 2026-09-23:

- **validator-v3**: the faucet accepted the grant (`200`), and it never
  committed. Height stayed at 5805 for over 20 minutes, `tip-certificate` nil,
  `pending 1`, `equivocators ["w1" "w3"]`.
- **sequencer**: every signature from a current client is refused
  `bad-signature`. It reports `code-version 12`; the signed payload
  `torihiki.auth` builds today has 37 fields, and the build it is still running
  predates that — the section above on Durable Objects not picking up deploys,
  again.

`script/nbb-classpath.cljk` resolves pins through sibling checkouts, so it
fails from a worktree whose siblings are not beside it (`unresolvable: io-ipld
… no checkout`); build the classpath from the main checkout and pass it in.

## Run

```bash
npm install
npm run build
npx wrangler deploy
kbb --backend sci --classpath <path-to>/torihiki/src client.cljk <url>   # signing client
```

## Validator duties (standalone)

`src/torihiki_node/duties.cljk` decides; `standalone.cljk` holds the sockets
and keys. Every duty only ever submits a signed transaction — the engine
decides what the set agrees on. `GET /duties` reports all of them.

| env | duty |
|---|---|
| `CHAIN_VALIDATORS=1` (+ `EPOCH_LENGTH`, `MIN_STAKE`) | genesis with the validator set, oracle and upgrades as chain state (a new chain — give it its own `CHAIN_ID`) |
| always, with a set | proofs of equivocation inga holds are submitted as `:equivocation-evidence` |
| always | a chain that voted past `duties/max-protocol` HALTS this binary (`/duties :halted`) |
| `BRIDGE_CONTRACT`, `BRIDGE_EVM_CHAIN_ID`, `BRIDGE_ASSET` | bridge mode at genesis |
| `BRIDGE_RPCS`, `BRIDGE_RPC_QUORUM`, `BRIDGE_FROM_BLOCK` | attest `TorihikiBridge` logs that `k` providers report identically, 12 blocks deep |
| `BRIDGE_SIGNER_KEY` | sign pending claims; `GET /bridge/signatures?epoch=N` |
| `ORACLE_MARKETS=1=BTC,...`, `ORACLE_VENUES`, `ORACLE_USD_PER_UNIT` | publish the median of ≥3 venues as `:oracle-submit` |

`script/bridge_relay.cljk` collects signatures and requests withdrawals (anyone
may run it). `script/bridge_e2e.cljk` runs deposit → credit → withdraw →
sign → relay → finalize → settle against anvil and four local validators.

**Not wired:** consensus does not follow epoch turns yet (inga runs the witness
list it started with; `/duties :set-mismatch` says when they differ), and the
Durable Object validator (`validator.cljk`) has none of these duties.
