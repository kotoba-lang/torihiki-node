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

## Run

```bash
npm install
npm run build
npx wrangler deploy
nbb --classpath <path-to>/torihiki/src client.cljs <url>   # signing client
```
