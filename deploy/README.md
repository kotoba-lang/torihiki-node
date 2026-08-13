# Placing the validators

Four hosts, or one host with four processes. The difference is not the
configuration — it is what an outage takes down with it.

## Four hosts (what a quorum of three means)

The set is four with a quorum of three. **That tolerates exactly one loss.**
On four hosts, one host dying leaves three and the chain continues. On one
host, the host dying leaves nothing, and every property this system has about
Byzantine faults becomes a property about a machine nobody is watching.

Run one host per validator unless the point is a trial.

```
apt install nodejs npm
npm i -g nbb
useradd -r -s /usr/sbin/nologin torihiki
git clone https://github.com/kotoba-lang/torihiki-node /opt/torihiki
cd /opt/torihiki && npm ci && clojure -Spath > ~torihiki/.torihiki/cp

install -d -m 0750 -o torihiki /etc/torihiki
install -m 0640 -o torihiki deploy/common.env.example /etc/torihiki/common.env
install -m 0600 -o torihiki deploy/w1.env.example     /etc/torihiki/w1.env
# edit both; SEED_W1 is 32 bytes of hex and belongs to this host alone

install -m 0644 deploy/torihiki@.service /etc/systemd/system/
systemctl enable --now torihiki@w1
```

## The seeds

`SEED_<W>` is a validator's signing key. **Set it.** Without one the node
derives a key from the chain id and the name, which is reproducible — which is
exactly why it is a devnet key. A chain whose keys can be derived by anyone who
knows its name has no validators, only names.

Generate one per host, on that host:

```
head -c 32 /dev/urandom | xxd -p -c 32
```

The public halves have to be agreed before the chain starts: leadership and
every certificate are checked against the set, so a key introduced later is a
validator nobody accepts.

## Ports

- **19401** between validators (WebSocket). Open to the other three only.
- **8801** the HTTP and JSON-RPC surface. Open to whoever should be able to
  read the exchange; it has no write path except `/tx`, which takes signed
  envelopes and refuses everything else.

## Is it working

```
deploy/health.sh http://127.0.0.1:8801 "http://10.0.0.12:8801 http://10.0.0.13:8801"
```

Two questions, and they fail apart: **is the chain moving**, and **is this
replica on the same one**. A replica can be perfectly healthy and alone, or in
touch with everybody and stuck. A check that asks only one of those will call
one of those states fine.

## What a replica recovers from by itself

- **A restart.** The log replays; a checkpoint every 500 blocks bounds how much.
- **Falling behind.** `inga.sync`, once it is close enough to be offered blocks
  that attach.
- **Being on a chain the quorum did not certify.** Measured: a replica handed a
  history with one block altered replayed to height 40, could not sync, and
  reported `repaired from the quorum at height 193` — then ran on at 861 beside
  859/861/863 with a matching block hash at 450.

## What it does not recover from

- **Losing its seed.** The key is the validator. There is no rotation
  transaction yet.
- **Two validators sharing a seed.** They would sign different votes as the
  same witness, which is equivocation, which is the one thing the system
  slashes for — committed by accident, by configuration.
