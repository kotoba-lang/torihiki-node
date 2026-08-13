(ns torihiki-node.validator
  "Four validators, deployed.

  Everything before this ran four replicas in one Node process on one machine.
  The sockets were real and the consensus was real, but 'deployed' and 'runs
  on my laptop' are different claims and only one of them had been made good.

  This is one Worker holding four Durable Objects — w1 to w4 — each running
  `inga.replica` with `torihiki.state` as its machine, exchanging messages
  across isolate boundaries on Cloudflare's network.

  ## Why HTTP between them and not WebSockets

  `inga.replica` is transport-agnostic: `on-message` and `on-tick` return an
  outbox and this posts it. A Durable Object can hold WebSocket connections
  and that would cost less per message, but it adds hibernation, reconnection
  and a socket lifecycle to a thing whose point is to show consensus running
  on real infrastructure. The cost is stated rather than hidden: every message
  is an HTTP round trip between isolates, so blocks come at hundreds of
  milliseconds rather than the single-digit milliseconds the in-process
  harness reaches.

  ## Keys are trust-on-first-use, and that is a devnet answer

  Each validator generates its Ed25519 key on first boot and publishes the
  public half at `/head`. The others fetch it and cache it. Whoever answers
  first defines the key, so a validator that is impersonated before it ever
  boots would be believed — which is exactly the class of attack the rest of
  this system refuses, and it is here because key distribution is a real
  problem this does not solve. `/head` says so in its own response.

  A vote from a witness whose key is not yet known is DROPPED, not deferred:
  fail closed, the same rule `inga.attest/lookup-verifier` states.

  ## Messages are queued and flushed once a tick, not dispatched on arrival

  Dispatching from `ingest` amplifies: one message in produces three out, each
  of which produces three more at the peer that receives it. In one process
  that is cheap and it is what the harness does. Over HTTP it is a fan-out
  that grows per round until it hits a subrequest limit, and the visible
  symptom is a chain that runs for a dozen blocks and stops — which reads as
  the alarm dying rather than as the network eating itself.

  So an outbox is queued and the tick flushes it: three POSTs per replica per
  tick, whatever happened in between. Bounded, and the same total work spread
  over one round instead of doubling inside it.

  ## The clock, and what is still wrong with it

  The alarm handler was not in the compiled bundle at all. `deftype` methods
  are renamed by the advanced compiler unless something stops it; `fetch`
  survives because the externs already know that name and `alarm` does not, so
  Cloudflare called a method that had been compiled away. Every symptom
  pointed at the alarm firing and doing nothing — the chain moved when `/step`
  was POSTed — so three fixes went into the SCHEDULING before the handler
  itself turned out to be missing. `goog.object/set` with a string literal
  attaches it under a name the compiler cannot touch.

  The alarm was firing the whole time. `wrangler tail` said so in thirty
  seconds — 153 alarm invocations, outcome ok, zero exceptions — after two
  rounds of fixes aimed at a clock that was not broken. `tickNow` was still
  calling `dispatch` with only the tick's own outbox while `ingest` queued
  into a buffer nothing drained, so the queue sat at one message and the chain
  sat at one block, and every symptom pointed at a dead alarm.

  The instrument was named as the next step two iterations before it was used.
  Both intervening fixes were correct and neither was the bug.

  ## Transactions are authenticated, and the check is asynchronous

  It was `(fn [_ _ _] true)` — every transaction in every block applied
  without asking whether the account had authorised it. Anyone who could POST
  to `/tx` could spend anyone else\u2019s collateral, and every replica would agree
  on the result.

  The check cannot be synchronous here: `torihiki.auth` calls `verify-fn`
  inside `apply-block`, and a Worker only has an asynchronous verifier. So the
  transactions in a PROPOSAL are verified when the proposal arrives, the
  answers are cached, and the synchronous seam consults the cache. There is
  time for it because the three-chain rule puts two blocks between a proposal
  and its commit — the same trade `torihiki-node` made when it verified a
  signature before applying a block rather than making `apply-block` async.

  A transaction whose signature was never checked verifies as FALSE, never as
  unknown. Fail closed, the rule `inga.attest/lookup-verifier` states.

  The regression that came with this was not in it. Versions 18 to 21 sat at
  height one exchanging new-views forever, and the cause was in engi: a
  new-view carrying the bootstrap genesis certificate was refused because that
  certificate has no signatures, so replicas that had not yet certified
  anything could not tell each other they had timed out. Their views drifted
  to 5, 6, 6 and 6 and no timeout certificate could form. Nothing threw,
  because refusing an unverifiable certificate is what that code is for.

  What found it was `/head` reporting the genesis hash, the tip hash and a
  count of message types seen. All four agreed on both hashes and the only
  type moving was `new-view`, which is a very short list of possible causes.

  And then a second one, of my own making: `rearm` overwrote the alarm on
  every request. Every inbound message runs it, so under a steady stream of
  peer traffic each arrival pushed the alarm another 400ms out and it never
  fired. Three of four validators sat at height zero having sent nothing, ever,
  while receiving a hundred and sixty messages; the fourth was the leader, got
  less traffic, found gaps, and ticked. A watchdog that resets the timer on
  every request is a timer that never expires under load.

  ## A proposer has to verify its own transactions

  Verification happens when a PROPOSAL arrives, which covers every replica
  except the one that made it: a proposer never receives its own block, so its
  cache had no answer for the transactions it had just put in it, and its own
  `apply-block` refused them as `:bad-signature` while every peer accepted
  them. Two replicas holding different balances for the same account — the
  disagreement this whole protocol exists to prevent, arrived at by an
  asymmetry in who checks what.

  So a transaction is verified when it is SUBMITTED as well, and refused at
  the edge if it does not hold up. That is not the authority — every replica
  still checks inside `apply-block`, which is where it has to be — it is the
  proposer answering the question about its own block before it asks it.

  ## Where it stands: further, and still not committing

  Bisecting one change at a time got the chain from height zero to height one
  with a certificate, which is three fixes further than it was:

  - a non-leader at height zero never reached `on-tick`, so its clock never
    started and it never sent a new-view. It was `start` OR `on-tick`; it has
    to be `start` AND `on-tick`.
  - the bootstrap block was proposed exactly once. Over HTTP to peers that may
    not exist yet, once is a message that can be lost, and then nothing.
  - the tip was never re-broadcast while it lacked a certificate, so the
    receivers that missed it had nothing to vote on again — and engi\u0027s
    matching fix, re-sending a vote when the same block arrives twice, could
    never fire because nothing sent the block twice.

  What is left, and it now has a name. The instrument reports, at the stall:

    w1 tip 2  votes-for-tip 2  view 16
    w2 tip 2  votes-for-tip 2  view 16
    w3 tip 1  votes-for-tip 0  view 21
    w4 tip 2  votes-for-tip 2  view 51

  Three replicas hold the height-2 block with two votes for it — one short of
  the quorum of three. The fourth is a block behind and will not vote for it,
  because its lock came from a certificate carrying a view the block does not
  beat. And the views are 16, 16, 21 and 51: the replicas are nowhere near
  each other.

  Views converge now — 246, 246, 246, 247 where they were 16, 16, 21 and 51 —
  and the chain still stops. The reading after the fix:

    w1 tip 2  votes-for-tip 2  view 246
    w2 tip 2  votes-for-tip 2  view 246
    w3 tip 1  votes-for-tip 2  view 246
    w4 tip 2  votes-for-tip 2  view 247

  Every replica is one vote short of the quorum of three, and the votes are
  split across two heights: two for the block at height one, two for the block
  at height two. w3 will not move to height two, so its vote can never join
  the three that would certify it, and the three that could certify it are
  each holding two.

  Convergence was necessary and not sufficient, and two more readings named
  what is left:

    w1 tip 2  lastprop no-parent 225   sync: offered 1 (2..2) adopted 0  does-not-attach
    w2 tip 2  lastprop already-voted   sync: offered 1 (2..2) adopted 0  does-not-attach
    w3 tip 1  lastprop already-voted   sync: offered 1 (2..2) adopted 0  BELOW-QUORUM
    w4 tip 2  lastprop already-voted   sync: offered 1 (2..2) adopted 0  does-not-attach

  A proposal for height 225 is in flight, so the chain does run — somewhere.
  What it does not do is take everybody with it.

  The `does-not-attach` refusals are benign: a sync-response is broadcast to
  everybody, so three replicas keep being offered a block they already hold.
  Wasteful, not fatal, and worth fixing by answering the asker instead of the
  room.

  The one that matters is `below-quorum`: `inga.sync` requires a quorum of
  VERIFIED signatures on the certificate inside a segment, and a replica that
  cannot verify enough of them is refused the block by the check that exists
  to let it in.

  One cause of that is now fixed and was not enough. A certificate remembered
  a single view while `vote-payload` covers the view, so votes cast in
  different views — which is what replicas that time out independently
  produce — could not all be reconstructed, and at most one signature could
  verify. Certificates carry a view per witness now.

  `below-quorum` persisted after that fix, and the next instrument read it
  properly rather than guessing a third time:

    witnesses  [w1 w2 w4]      three, so the threshold is met
    sigs       [w1 w2 w4]      all present
    views      null            <- the certificate carries none
    qc-view    2
    attest     bad-signature
    per-witness {w1 false, w2 false, w4 false}

  All three signatures fail, and the certificate has no per-witness views, so
  every payload is rebuilt from qc-view 2 while the votes were signed at the
  views their replicas had reached. The encode/decode path preserves views —
  checked directly, built with views 5, 6 and 7 and decoded with the same — so
  this certificate was BUILT without them.

  Certificates are tagged with the path that built them now, and the reading
  says something simpler and worse than a shape problem:

    w1 tip 2  tip-certificate null
    w2 tip 2  tip-certificate null
    w3 tip 1  tip-certificate null
    w4 tip 2  tip-certificate null

  NOBODY holds a certificate for their own tip. Not one of the four. So the
  certificate that keeps being refused as `below-quorum` is not the one
  blocking them — it is a certificate for height 1 arriving inside a
  sync-response, carrying qc-view 2 and no per-witness views, which every
  replica was already past.

  What blocks all four is that no block any of them adopted has ever been
  certified. Votes exist — the earlier readings counted two per tip — and a
  quorum of three is never assembled, so `:qcs` stays empty and `propose`
  cannot fire for anybody. The chain is not deadlocked on a bad certificate.
  It has never made one.

  ## The deployed chain was DOWN, and this is how it was found

  It ran at version 27, height a hundred and climbing. It does not run now,
  pinned to the same engi commit, on freshly created objects. I do not know
  why, and the reason I do not know is that I changed five things between
  observations and then changed five more trying to get back — which is the
  mistake this file has already recorded twice and is the one that cost the
  most.

  What is known and worth keeping is below: a dead leader holds its turn
  forever, and a wiped replica does not catch up. Both were found by breaking
  the chain deliberately, and both are real regardless of what is running.

  ## /reset, so catching up can actually be tested

  `inga.sync` decides what a replica that has been away may believe from a
  peer, and until now nothing had made a deployed replica go away. Every
  untested path in this system has turned out to be a broken one, so there is
  a route that wipes one validator back to genesis and lets it try to rejoin.

  It is unauthenticated, which is fine for a devnet and would not be anywhere
  else: it destroys one replica\u0027s state, and with a quorum of three out of
  four, two calls stop the chain. Named here rather than left to be found.

  ## The chain is persisted, because a Durable Object restarts

  A replica rebuilt from genesis on every boot proposes a fresh block for a
  height it already proposed, and a Durable Object is evicted routinely. The
  network accumulated incompatible height-one proposals until no two votes
  were for the same decision — three votes, three block hashes, one height.

  So every adopted block is written to storage and replayed through
  `inga.replica/replay` on boot. Not re-verified: re-checking is re-litigating
  a decision this replica already made and recorded, the same distinction the
  sequencer in this repo draws when it replays its transaction log.

  Replay also restores the heights already voted at. Without that a restart
  votes a second time at a height it already voted at, which is equivocation —
  committed by accident, against itself.

  Persistence alone was not enough and could not have been. A block carried
  the wall clock in its header, so a leader that restarted proposed a
  different block for the same height, and the write only had to lose one race
  against eviction for the split to happen again. engi derives a block time
  from its parent now, which makes proposing a pure function of the chain — a
  restarted leader re-proposes the byte-identical block and nothing has to win
  a race. Persistence still earns its keep: it is what stops a restart from
  voting twice at a height it already voted at.

  ## The deadlock at genesis

  The chain sat at height one with no certificates and nothing on the wire —
  262 alarms, 786 peer fetches, zero messages sent. The absence was the clue:
  nothing throws when a replica has nothing to say.

  `inga.pacemaker` starts with a deadline of 0 and `on-tick` read that as no
  clock yet. A replica that never saw a certificate never got a deadline,
  never timed out, never sent a new-view, and therefore never got a
  certificate. In one process the first certificate forms in a millisecond and
  that state is never occupied; over HTTP, one lost vote at genesis is a chain
  that sits there forever. Fixed in engi, not here.

  ## Three bugs the deployment had that the in-process harness could not

  - **The witness name was not persisted.** A Durable Object can be evicted
    between scheduling an alarm and the alarm firing, and the alarm arrives
    with no request to read a name out of. Three of the four woke up believing
    they were w1.
  - **The key was regenerated on every boot.** Eviction is routine, so a
    validator came back with a new identity while every peer held the old
    public key, and every vote it sent verified as false. The symptom was
    three replicas holding two votes each, one short of quorum, with nothing
    to read: signatures that do not verify are dropped silently, which is
    correct. A validator whose identity does not survive a restart is not a
    validator.
  - **`setAlarm` was fired and not returned.** A Durable Object may be put to
    sleep as soon as the handler resolves, so a write still in flight is a
    tick that never happens."
  (:require [clojure.set :as set]
            [goog.object :as gobj]
            [inga.attest :as att]
            [inga.consensus :as c]
            [inga.replica :as r]
            [inga.sync :as isync]
            [inga.wire :as wire]
            [kotoba.bytes.sha256 :as sha]
            [torihiki.address :as addr]
            [torihiki.commit :as cm]
            [ipld.core :as ipld]
            [ipld.link :as ilink]
            [torihiki-chart.candle :as cndl]
            [torihiki.auth :as tauth]
            [torihiki.evm :as evm]
            [torihiki.evm.interp :as evmi]
            [torihiki.keccak :as kc]
            [torihiki.api :as api]
            [torihiki.book :as bk]
            [torihiki.clearing :as cl]
            [torihiki.snapshot :as tsnap]
            [torihiki.state :as st]))

(goog-define genesis-set "v1")
;; Which genesis validator set this BUILD carries. A compile-time constant,
;; not an environment variable: a Worker has no `process.env`, and a value the
;; running code could be handed at request time would let the same build claim
;; two different validator sets — which is the one thing a genesis set must
;; not be able to do.
;;
;; `shadow-cljs release validator-v2` sets it to "v2". See `validator-keys`.

(def ^:const chain-id
  "**Not renamed when the consensus layer moved to inga, on purpose.**

  A chain id is domain separation: every vote and every transaction envelope
  signs over it, so changing the string invalidates every signature already on
  the chain and splits the replicas from their own history. It names a chain,
  not a library, and this chain was started under that name."
  "torihiki-engi-devnet-1")
(def ^:const pkcs8-ed25519-prefix
  "The 16 bytes PKCS8 puts in front of a raw Ed25519 seed. An Ed25519 private
  key IS its 32-byte seed; this header is what `importKey \"pkcs8\"` expects
  around it, and it is constant, so wrapping a seed is not a key format of my
  own invention."
  #js [0x30 0x2e 0x02 0x01 0x00 0x30 0x05 0x06 0x03 0x2b 0x65 0x70
       0x04 0x22 0x04 0x20])

(defn- keypair-from-seed
  "A keypair from a 32-byte Ed25519 seed (base64). Returns a promise of
  `#js {:privateKey k :pub b64}`.

  Replaces `derive-keypair`, which built the seed by hashing the chain id and
  a witness name. That function is GONE rather than kept as a fallback: a
  fallback to a publicly computable key is the same hole with a condition in
  front of it, and the condition — a missing secret — is the state a fresh
  deployment is in."
  [seed-b64]
  (let [bin (js/atob seed-b64)
        seed (js/Uint8Array. (.-length bin))
        _ (dotimes [i (.-length bin)] (aset seed i (.charCodeAt bin i)))
        pk (js/Uint8Array. 48)]
    (when (not= 32 (.-length seed))
      (throw (js/Error. "seed is not 32 bytes")))
    (.set pk (js/Uint8Array.from pkcs8-ed25519-prefix) 0)
    (.set pk seed 16)
    (-> (js/crypto.subtle.importKey "pkcs8" pk #js {:name "Ed25519"} true #js ["sign"])
        (.then (fn [sk]
                 (-> (js/crypto.subtle.exportKey "jwk" sk)
                     (.then (fn [^js j]
                              (let [x (aget j "x")
                                    std (-> x (.replace (js/RegExp. "-" "g") "+")
                                            (.replace (js/RegExp. "_" "g") "/"))
                                    pad (case (mod (.-length std) 4) 2 "==" 3 "=" "")]
                                #js {:privateKey sk :pub (str std pad)})))))))))

(defn- secret-keypair
  "The keypair whose seed is in `<NAME>_KEY`. Refuses rather than falls back."
  [^js env name]
  (let [v (aget env (str (.toUpperCase name) "_KEY"))]
    (if (and (string? v) (pos? (.-length v)))
      (keypair-from-seed v)
      (js/Promise.reject
       (js/Error. (str "no secret for " name " — set " (.toUpperCase name) "_KEY"))))))


;; `derive-keypair` used to live here: it hashed the chain id and a witness
;; name into a seed. It is DELETED, not disabled. The chain id is printed by
;; `/head`, so that function was a public constructor for every private key in
;; the validator set, and a dead one in the file is a live one after the next
;; refactor that "restores a fallback".

(def ^:const code-version
  "Bumped on every deploy, because it is the only way to tell whether one took.

  Measured 2026-08-13: after `wrangler deploy` reported a new Version ID, all
  four replicas went on reporting the PREVIOUS value for as long as they were
  sampled. A Durable Object runs the code it booted with until it is evicted,
  and these fire an alarm every few tens of milliseconds, so they never go
  idle enough to be evicted. **Deployed and running are different facts** —
  ADR-2608020330 says so, and this constant is what makes the difference
  visible instead of assumed."
  "135")

(defn- do-name
  "The Durable Object id for a witness. NO VERSION IN IT.

  It used to carry `code-version`, on the belief that a DO with a
  self-rescheduling 400ms alarm never idles and so never picks up a deploy.
  That belief was wrong, and `wrangler tail` shows it: alarm events on objects
  created many versions ago carry the CURRENT scriptVersion. Cloudflare runs
  every Durable Object on the latest deployed script.

  So the versioned name bought nothing and cost the chain. A bump ABANDONS the
  previous objects — which keep ticking, keep believing their persisted
  witness name, and, since keys are derived from that name, keep signing votes
  everybody accepts. Fourteen generations in one session gave several objects
  each claiming to be w1, voting at different heights, and the deployment
  reported `equivocators [w4]` with no Byzantine node deployed.

  One object per witness, forever. See `tickNow` for what stops the ones
  already abandoned."
  [w]
  w)
(def ^:const market-id
  "The market a read route answers about when the caller does not say.

  Not `the` market any more — see `markets`. It is the default so that every
  existing client keeps working unchanged, and every read route takes `?m=`."
  1)
(def witnesses
  "Seven, not four.
  
  A Durable Object is evicted constantly, and four witnesses with a quorum of
  three have ZERO margin under that: evict one and exactly quorum remains, so
  a single lost message costs a round. Measured in engi's harness, sixty
  seconds, evicting one replica per second against not evicting at all:

    N=4   377 -> 168 blocks   2.24x penalty
    N=7   260 -> 153 blocks   1.70x

  Seven leaves six against a quorum of five and has one to spare. It buys that
  with throughput — seven replicas are more messages per round, 260 against
  377 with nothing being evicted — and the deployment is the case where things
  ARE being evicted, which is where the trade pays.

  Adding a witness is NOT this line any more. It used to be — keys were
  derived from the witness name, so w5 through w7 needed nothing distributed —
  and that is the hole the real keys closed. A new witness now needs a
  generated seed, its public half reviewed into `validator-keys`, and its
  private half set as a Worker secret; a replica whose secret is missing or
  disagrees with the pin refuses to start, which is the loud failure and the
  right one. Editing this vector alone gets a witness that cannot boot."
  ;; REVERTED to four. Seven was deployed and the chain stopped at height two
  ;; with four votes against a quorum of five — three of the seven never voted
  ;; at all, where four witnesses had reached 139 in half the time. Whatever
  ;; the harness is modelling about churn, it is not modelling what these seven
  ;; Durable Objects do, and the measured penalty is not worth a chain that
  ;; does not run. The engi measurement stands; this deployment keeps four
  ;; until the three silent replicas are explained.
  ["w1" "w2" "w3" "w4"])

(def validator-keys
  "The public half of the validator set, and of the bridge. **Genesis data.**

  Before this, every key was DERIVED from the chain id and a name — and the
  chain id is printed by `/head`. Demonstrated against the live deployment:
  deriving from public information reproduced all four validators' published
  public keys exactly, which means anybody could compute all four PRIVATE
  keys, forge four votes, and manufacture a certificate. The BFT guarantee was
  zero, and the page said `4 replicas, agreeing`.

  The private halves are now Worker secrets (`W1_KEY` … `BRIDGE_KEY`, each the
  32-byte Ed25519 seed, base64). Nothing derives them and nothing prints them.

  ## Why the public keys live HERE and not in a first-use exchange

  Peers used to learn each other's keys from `/head` — trust on first use,
  which is fine when the key was derivable anyway (there was nothing to
  impersonate) and is the weak point the moment keys are real: whoever answers
  first is believed. A validator set is exactly the thing a chain must know
  before it starts, so it is genesis data, in the source, reviewed with it."
  (if (= "v2" genesis-set)
    ;; A second, independently keyed validator set.
    ;;
    ;; It exists because the first deployment cannot be given new code: those
    ;; Durable Objects run what they booted with, never go idle, and survived
    ;; both a deploy and a `renamed_classes` migration (measured 2026-08-13).
    ;; Everything written since then — spot markets, the reserve attestation,
    ;; the two-chain commit, chosen leverage, sub-accounts — is verified only
    ;; by tests, and a test is not a deployment.
    ;;
    ;; So: a parallel chain, with its own keys, that CAN run the new code. The
    ;; first deployment is untouched; this one is where the new code is
    ;; answered for. Both key sets live here, in source, reviewed with the
    ;; code, for the reason the paragraph above this one gives.
    {"w1" "1aJxhZfNXYrOfM6MoInHY3N60QvWk3FQrZAOmzp34Ww="
     "w2" "gzo4VJ1tUqS7Xsm39C3LJ82A88+XHuJhTZA52KTciuU="
     "w3" "Zn9VaTKLffouJfWAV18/rTqT9DpN41jURl9bqRJbdRM="
     "w4" "3zUgkgYGPkI/fP9zNSBBHxWS28S+RFo3TPv0NB7OB88="}
    {"w1" "futt80Tbbqc50hej5X7xqWjG0oFLy3yrBAHDrOA0td0="
     "w2" "K8ZA5PssVguLJcRZjzHzZ+vCQQJoLl5VRJ76gbxTqCk="
     "w3" "iRNrRiAbOb8+HoTkDF63Xp6B1zFB1QApNiSPzb5P/gQ="
     "w4" "GD3mDeP0zFMnyzCQpz+09+LWcoQcrdL4EvcV9jpZFqM="}))

(def bridge-pubkey
  "The faucet's public key — the account that may mint collateral.

  Per genesis set, like the validator keys and for the same reason."
  (if (= "v2" genesis-set)
    "o9Fpro5qdCeeY2LZnxQS16/Yir4LDghHTjHL6PSPOek="
    "jmBeKHD9Pb6GYmlFUfnFwfoci+pSE5rZ+ALlAfnP00o="))

(defn key-distribution
  "What `/head` says about where the validator set's public keys came from —
  READ OFF `validator-keys`, not asserted.

  It used to be the constant string `trust-on-first-use — a devnet answer, not
  a real one`, and it outlived the thing it described by eleven days. The
  commit that pinned these keys deleted derivation, put the public halves in
  `validator-keys` and the private halves in Worker secrets; this line went on
  saying the opposite, and the live endpoint said it too.

  The error pointed at a stronger system than the deployment was, which is the
  harmless direction and still a false self-report — and `/head` is the one
  thing here that has to be true. Two ADRs already turn on that: the sequencer
  answers `consensus: none` about itself because a service that stays quiet
  about its posture is assumed to be the other kind, and `code-version` exists
  because a Durable Object runs the code it booted with, so a deploy is not a
  fact about what is running. A hand-written claim in `/head` has the same
  defect as a hand-written version: it is a second copy of something already
  known, and the copy is the one that goes stale.

  So this is derived. A witness with no pinned key is precisely one the
  boot-time fill leaves empty, and that is the case where `catchUp` asks a
  peer for the key and believes whichever object answers — so the sentence and
  the behaviour can only change together."
  []
  (let [missing (vec (remove #(get validator-keys %) witnesses))]
    (if (seq missing)
      (str "trust-on-first-use for " (apply str (interpose "," missing))
           " — " (count missing) " of " (count witnesses)
           " witnesses have no genesis key, and their keys are learned from"
           " whichever peer answers first")
      (str "genesis — " (count witnesses) " public keys pinned in source,"
           " private halves are Worker secrets; a replica whose secret"
           " disagrees with the pin refuses to start"))))

(def ^:const checkpoint-every
  "Write a checkpoint every N committed blocks.

  This is the number that decides how much of the log a restart still has to
  fold: at worst `checkpoint-every` blocks, never the whole chain. 100 keeps
  the tail replay well inside one invocation while writing one extra key per
  hundred blocks.

  The pages `catchUp` folds are still bounded — a checkpoint changes how MANY
  pages there are, not how big one is. Both bounds are needed: without the
  page bound one invocation can exceed its budget, and without the checkpoint
  the number of invocations grows with the chain."
  100)

(def ^:const checkpoints-kept
  "How many checkpoints to keep.

  Two, originally, and for one reason: a checkpoint written while the object is
  being reset could be a half-written key, and the older one is what makes that
  survivable rather than fatal. That reason still holds and two satisfied it.

  Eight, because a second reader appeared. `adoptIfOutvoted` compares this
  replica's state against a quorum's AT THE SAME HEIGHT, so it needs a
  checkpoint height this replica and every peer all hold. Checkpoints land
  every hundred blocks at roughly two and a half blocks a second, so two of
  them is about eighty seconds of history — and four replicas that restart at
  different moments do not reliably overlap inside eighty seconds. Measured:
  the check ran, answered `no-shared-checkpoint-height`, and the divergent
  replica stayed divergent.

  Eight is about five minutes, and a checkpoint is roughly 22 KB, so the whole
  window is under 200 KB per replica. The cost of being wrong in this
  direction is disk; the cost of being wrong in the other is a replica that
  cannot be repaired."
  8)

(defn- blk-key
  "The storage key for a block. Zero-padded to twelve digits so lexicographic
  order IS height order — which is what makes `startAfter`, `start` and `end`
  usable as height cursors."
  [h]
  (str "blk:" (.padStart (str h) 12 "0")))

(defn- ckpt-key [h] (str "snap:" (.padStart (str h) 12 "0")))

(def ^:const stall-shape
  "What the deployed chain looks like when it stops, measured 2026-08-13 at
  height 40853 with every replica on the same code.

      seen-types  sync-response 8238   sync-request 4611
                  proposal      3324   vote          991
      votes-for-tip 1-2 (quorum 3), voted-at-tip? true on all three,
      last-tip-vote [40853, 43403] — the re-vote IS running, once per view
      view 43403 against height 40853 — two and a half thousand views ahead

  **The recovery is eating the transport.** Every timed-out view sends a
  sync-request to all three peers; each answers with a segment; the votes that
  would certify the tip are one message in nine. `inga.replica/on-tick` asks
  once per view precisely so this cannot become a flood — and at two thousand
  views past the height, once per view IS a flood.

  Nothing here is a fix. It is written down because the shape is specific and
  the next person to look will otherwise start from `votes-for-tip 1`, which
  says a vote is missing and not that it was outnumbered nine to one by the
  machinery meant to help it."
  :documented)

(def ^:const tick-ms
  "How often a replica wakes itself to make progress. **25.**

  It was 400, then 200 to answer a question about a stall at height 225 (was
  the ceiling the chain or the clock?). Neither number was ever chosen for
  speed, and at 200 blocks landed every 462 ms while Durable-Object-to-
  Durable-Object HTTP costs tens of milliseconds — an order of magnitude
  between the cadence and the transport, all of it in waiting.

  Measured, live, 45 seconds per point, all four replicas sampled throughout:

  ```
    tick   interval   blocks/s   catching-up   equivocators   split roots
     200     462 ms      2.17         0%            none          none
     100     345 ms      2.89         0%            none          none
      50     259 ms      3.86         0%            none          none
      25     185 ms      5.40         0%            none          none
      10     126 ms      7.96         0%            none          none
       5    STOPPED      0.00       6.5%            none          none
  ```

  **The cliff is between 10 and 5, and it is a cliff rather than a slope**: the
  chain did not slow down at 5, it stopped — zero blocks in 67 seconds — and
  no replica reported an error, equivocated, or disagreed about a root while it
  did. That is the same shape ADR-2608025400 measured from the other side
  (20 ms of delivery delay took committed blocks to zero): this implementation
  needs a round to fit between ticks, and when it does not, nothing says so.

  **25 and not 10**, giving up 60 ms per block. 10 measured perfectly healthy
  and sits one step from a cliff whose only symptom is silence, and `witnesses`
  already records what running with zero margin costs — four replicas and a
  quorum of three, where a single eviction leaves exactly quorum. Taking the
  best measured number would be making that mistake again in a different
  variable.

  Still 2.5x the rate this started at, and the remaining distance to
  Hyperliquid's ~0.07 s is not here — it is the transport, which is HTTP
  between isolates and answers to co-location rather than to a constant."
  ;; **Back to 25**, and now the number is measured rather than inherited.
  ;;
  ;; The table above was taken when every message between replicas was its own
  ;; HTTP POST, and it put a cliff between 10 and 5 — at 5 the chain did not
  ;; slow, it STOPPED. 25 was chosen to keep two steps from a cliff whose only
  ;; symptom is silence. Messages now travel over standing sockets and are
  ;; flushed as the batch is folded rather than on the next tick, so the
  ;; condition the cliff was made of — a round that no longer fits between
  ;; ticks — is not the same condition. A number kept for a reason that has
  ;; been removed is a number nobody measured. So it was re-measured.
  ;;
  ;; At 10, over sockets, live on all four replicas:
  ;;
  ;;   tick gap   min 14-50   p50 42-57   max 55-1015
  ;;   block      min 18-37   p50 154-279
  ;;
  ;; **The alarm does not fire at 10 ms.** Asked for 10 it delivered a median
  ;; gap of 42-57 — worse than the 33-43 it delivered when asked for 25 — and
  ;; the block median went with it. A Durable Object alarm has a cadence floor
  ;; somewhere around 35-55 ms and asking for less buys more scheduling, not
  ;; more speed.
  ;;
  ;; That is the honest end of what a constant can do here. The block MINIMUM
  ;; is 18-37 ms, which is the event-driven path — socket in, vote out,
  ;; certificate, proposal — running without waiting for any alarm at all, and
  ;; it is already faster than the target. What is left is that this path does
  ;; not fire on every block, and the fallback is a clock that cannot go
  ;; faster than about 40 ms a step.
  25)

(def ^:const deliver-cap-ms
  "How long a tick will wait for its own messages to be delivered. **40.**

  Not a timeout — the sends are not cancelled and `deliver-ms` still reports
  what they really cost. It is the point past which waiting stops being the
  local clock's business. 40 is above the measured p50 round trip (3-18 ms
  same colo, 24-36 ms to the one replica in another city) and well under the
  247 ms median block it is meant to cut into."
  40)

(def ^:const replay-page
  "How many persisted blocks one invocation folds on the way back up.

  There has to be a number here at all because a Durable Object's CPU budget
  is per invocation and the log is unbounded, so `replay the log on boot` is a
  cost that grows past the budget and then stays past it. See `catchUp`.

  25 is chosen to be obviously under budget rather than to be optimal — a
  chain at height 500 catches up in twenty ticks, four seconds at `tick-ms`,
  which is a startup nobody watches. Tuning it upward trades that invisible
  delay for the failure mode this exists to remove, which is not a trade worth
  making until something measures the real per-block cost."
  25)

;; ── the machine ─────────────────────────────────────────────────────────────

(def markets
  "The markets this chain runs.

  One, until now, and the engine's multi-market shape had never been built on
  — `:books` has always been a map keyed by market id and every transaction
  has always named its market, so the claim was structure without evidence.

  A second market is what turns that into a fact, and it is also where the
  per-market seams get tested by something other than a unit test: each market
  has its own book, its own oracle, its own mark, its own funding accumulator
  and its own liquidation sweep. A bug in any of those reads as `market 2 is
  quiet`, which is why the e2e trades on 2 rather than only reading it.

  Ids are stable and never reused: an id is what a transaction names, so
  renumbering markets would silently re-aim orders already signed.

  Each market costs a book slab, so the ladder is deliberately small here —
  4096 ticks is plenty for a devnet and the memory is per market."
  [(assoc (cl/market {:id 1 :symbol "BTC-PERP" :max-leverage 40 :tick 10 :lot 1})
          :taker-fee-rate 350000
          :maker-fee-rate 100000
          ;; Volume tiers. The first is the base, so an account with no
          ;; history pays exactly what it paid before tiers existed — the
          ;; schedule adds a discount, it does not reprice the floor.
          ;;
          ;; The window is `torihiki.clearing/volume-epoch-blocks` wide in
          ;; blocks, not days: the engine has no clock. At the cadence this
          ;; chain runs it is a few hours, which is a devnet number and not a
          ;; policy — a real schedule would be set by whoever prices the
          ;; venue.
          :fee-tiers [{:min-volume 0          :taker-fee-rate 350000 :maker-fee-rate 100000}
                      {:min-volume 100000000 :taker-fee-rate 250000 :maker-fee-rate 50000}
                      {:min-volume 1000000000 :taker-fee-rate 150000 :maker-fee-rate 0}])
   ;; A spot market: balances change hands, nothing is margined. `:asset` is
   ;; the id of what is bought and sold; the quote is the collateral the venue
   ;; keeps its books in.
   ;;
   ;; It was held out of this vector while the first deployment could not take
   ;; new code — listing a market the chain does not have made
   ;; `listMissingMarkets` queue a PRICE for it too, and a guaranteed-invalid
   ;; transaction in a strictly sequential nonce line is a wall every later
   ;; bridge transaction queues behind. That filter is fixed, and this build is
   ;; the one that actually runs.
   (assoc (cl/market {:id 3 :symbol "BTC-USD" :max-leverage 1 :tick 10 :lot 1})
          :kind :spot :asset 77
          :taker-fee-rate 350000
          :maker-fee-rate 100000)
   (assoc (cl/market {:id 2 :symbol "ETH-PERP" :max-leverage 20 :tick 10 :lot 1})
          :taker-fee-rate 500000
          :maker-fee-rate 100000
          :fee-tiers [{:min-volume 0          :taker-fee-rate 500000 :maker-fee-rate 100000}
                      {:min-volume 100000000 :taker-fee-rate 350000 :maker-fee-rate 50000}
                      {:min-volume 1000000000 :taker-fee-rate 200000 :maker-fee-rate 0}])])

(def market
  "The first market. Kept because the read routes default to a market when the
  caller does not name one, and because a terminal that has always shown one
  book must keep showing that one."
  (first markets))

(def ^:const bridge-name
  "What the faucet's key is derived from. Part of the derivation, so changing
  it changes the account and orphans every deposit the old one made."
  "faucet")

(def ^:const checkpointed-candles
  "How many block candles ride along in a checkpoint.

  Fewer than `candle-retention`, because a Durable Object storage value has a
  hard size ceiling and the checkpoint is already carrying the whole exchange.
  A checkpoint that grew past it would fail to write — and `checkpoint!`
  catches its own errors, so the failure would be silent and the replica would
  fall back to replaying from further and further back.

  What is lost past this bound is HISTORY, not state: `/candles` reports its
  own horizon in `retained-from`, so a shorter chart after a restart is
  visible rather than implied."
  600)

(def ^:const tape-retention
  "Prints kept in the machine, across all markets.

  200 while there was one market and one reader. It is now the only place a
  per-account fill history can come from, and it is shared by every market, so
  a busy book would evict another market's prints in a few blocks. 2000 is
  bounded for the same reason 200 was — this rides in every checkpoint — and
  is roughly ten times the window it replaces.

  What is lost past it is HISTORY, not state: `/fills` reports the height it
  can see back to, so a short answer is visible rather than implied."
  2000)

(def ^:const candle-retention
  "Block candles kept in the machine. Only blocks that traded make one.

  Bounded because this lives in the machine state, which every replica holds
  in memory and writes into every checkpoint — an unbounded index would grow
  the thing the bounded resume exists to keep small."
  4000)

(def ^:const adopt-check-ms
  "How often a replica asks whether it has been outvoted on state.

  Three fetches and a restore, so not every tick. Sixty seconds is far shorter
  than the hours the last divergence ran undetected and far longer than the
  question costs."
  60000)

(def ^:const faucet-nonce-lead
  "How many grants may be in flight ahead of the committed bridge nonce."
  8)

(def ^:const faucet-grant
  "One grant, in cents. Matches what the terminal's button has always said."
  10000000)

;; The bridge's account id, resolved once at boot.
;;
;; `genesis` has to be synchronous — it is the machine's `:init-fn` — and the
;; account id is `addr/derive` of a public key that only WebCrypto can produce.
;; So `boot` resolves it before it builds the replica, and this holds it.
;;
;; Every replica derives the SAME key from the chain id and the name above, so
;; they all reach the same bridge without being told what it is. That is what
;; keeps this out of consensus: a bridge that had to be configured could be
;; configured differently on one replica, and that replica would accept
;; deposits the others refused while agreeing on every block.
;;
;; (`defonce` takes no docstring in ClojureScript.)
(defonce faucet-account (atom nil))

;; The accounts allowed to publish a price, resolved at boot like the bridge.
;;
;; Empty, `torihiki.api` leaves the DIRECT setter open — and this deployment
;; configured none, so any account could set the oracle. Demonstrated against
;; the live chain: an account created seconds earlier, holding no collateral
;; and named nowhere, moved the oracle from 1000 to 1001 and the mark followed
;; it. Margin and liquidation read the mark, so the same transaction with a
;; larger number liquidates everybody.
;;
;; The mechanism was already right — `api/validate` closes the direct setter
;; as soon as publishers exist, and says so — it was the CONFIGURATION that
;; left both doors open. This is the configuration.
;;
;; The four validators are the publishers. On this devnet their keys are
;; derivable from the chain id, so this restricts the door rather than locking
;; it; the lock is real keys, which is a separate change. Restricting it still
;; removes the case where a stranger needs nothing at all.
(defonce publisher-accounts (atom #{}))

(defn genesis []
  (-> (st/new-exchange {:markets markets
                        :book-opts {:n-levels 65536 :cap 16384 :ev-cap 8192}
                        ;; Collateral has an issuer.
                        ;;
                        ;; Before this it had none: `:bridge-authority` was
                        ;; nil, so `api/validate` let ANY account deposit ANY
                        ;; amount to itself. Every balance was minted by
                        ;; whoever wanted it, and the clearinghouse's
                        ;; arithmetic — margin, liquidation, the insurance
                        ;; fund — was exact about a quantity that could be
                        ;; conjured, which is the kind of wrong where every
                        ;; individual number is right.
                        ;;
                        ;; The owner chose (2026-08-05) the chain's own
                        ;; certificate over an external asset: the issuer is a
                        ;; key, the certificate is its signature on the
                        ;; deposit — already verified by every replica and
                        ;; already replay-protected by the same strictly
                        ;; sequential nonce as every other transaction — and
                        ;; `/faucet` is the only thing that holds it.
                        :bridge-authority
                        (or @faucet-account
                            (throw (js/Error. "genesis before the faucet key")))
                        ;; Closing this door does NOT freeze the market. With
                        ;; publishers configured and none of them submitting,
                        ;; `:oracle` keeps its genesis value and
                        ;; `:oracle-stale` is only recomputed when a
                        ;; submission arrives — so the price simply stops
                        ;; moving, which is what it already did. The devnet
                        ;; has no price feed; what it had was a door.
                        :oracle-publishers
                        (let [ps @publisher-accounts]
                          (if (seq ps)
                            ps
                            (throw (js/Error. "genesis before the publisher set"))))})
      (as-> ex (reduce (fn [e m]
                         ;; Every market needs a price before anything can be
                         ;; margined on it. Same genesis number for both: a
                         ;; devnet has no feed, and two markets that start at
                         ;; different prices would imply one.
                         (st/apply-tx e {:tx :oracle :market (:id m) :price 1000}))
                       ex markets))))

(defn- decode-tx [s]
  (let [m (js->clj (js/JSON.parse s) :keywordize-keys true)]
    (update m :tx (fn [t] (cond-> t (string? (:tx t)) (update :tx keyword))))))

;; ── crypto at the edges ─────────────────────────────────────────────────────

(defn- b64 [^js buf]
  (let [a (js/Uint8Array. buf)]
    (js/btoa (.apply js/String.fromCharCode nil a))))

(defn- b64-> [s]
  (let [bin (js/atob s) n (.-length bin) out (js/Uint8Array. n)]
    (dotimes [i n] (aset out i (.charCodeAt bin i)))
    out))

(defn block-node
  "A block as an IPLD node — the DAG-CBOR shape its CID addresses.

  ## Why the chain has a CID at all now

  `inga` has a whole content-addressed layer (`inga.ref`, `inga.head`,
  `inga.retrieval`) built on `kotobase.storage`, and `inga.ref`'s idea is the
  one ADR-2608038000 names as the replacement for a single vendor's
  conditional write: **a 2f+1 quorum certificate IS a conditional write.**
  None of it was reachable from here — `inga.replica` requires consensus,
  pacemaker, quorum, attest, stake, sync and wire, and nothing else — and a
  head record in that layer names a `cid`, which this chain did not have. Its
  blocks were identified by a bare SHA-256 hex string.

  So this is the first wire, and the order matters: a store addressed by CID
  is pointless while the thing being stored has no CID.

  ## The fields are exactly what the hash already covered

  `inga.consensus/canonical-block` commits to height, parent, proposals,
  proposer and ts — and NOT to the justify QC, which is a claim about the
  parent rather than about this block. Committing to more here would change
  what a block hash MEANS, not just how it is spelled.

  ## The parent is a LINK, not a string

  Tag 42. That is the difference between a chain whose blocks happen to
  contain a hash and a DAG that any IPLD tool can walk: `ipld.core/decode`
  hands back a `Link`, and following it is a lookup rather than a convention
  somebody has to know. Genesis has no parent and carries nil rather than the
  string `\"genesis\"` — a sentinel that is not a CID would have to be special
  cased by every reader."
  [{:keys [inga.block/height inga.block/parent-hash inga.block/proposals
           inga.block/proposer inga.block/ts]}]
  {"height" height
   "parent" (when (and parent-hash (not= "genesis" parent-hash))
              (ilink/link parent-hash))
   "proposals" (vec proposals)
   "proposer" proposer
   "ts" ts})

(defn block-cid
  "CIDv1, dag-cbor, sha2-256 — the block's identity."
  [b]
  (ipld/cid (ipld/encode (block-node b))))

(defn- block-hash
  "SHA-256 of the canonical block string, synchronously.

  It was FNV-1a, because WebCrypto's digest is async and `hash-fn` is not —
  the replica hashes a block while deciding whether to vote for it, and making
  that path async would push a platform concern into consensus code. The
  answer was not to weaken the hash but to use one that is already
  synchronous: `kotoba.bytes.sha256` is pure `.cljc` and `torihiki.state`
  computes its state root with it. A block identifier a peer can forge
  collisions in is a peer that can make two blocks look like one."
  [s]
  (sha/sha256-hex s))

;; ── the replica ─────────────────────────────────────────────────────────────

(defn- occupied-levels
  "Every occupied price level on `side`, best first.

  `bk/next-occupied` goes strictly BEYOND the level it is given, descending for
  bids and ascending for asks, and answers -1 at the end — so the walk starts
  at `bk/best` and stops on -1. Written out rather than expressed as `iterate`
  because the two sides run in opposite directions and a single arithmetic
  step that looked right for asks silently returned nothing for bids."
  [book side]
  (loop [l (bk/best book side) acc []]
    (if (neg? l) acc (recur (bk/next-occupied book side l) (conj acc l)))))

(defn- market-param
  "The market a read route is about: `?m=`, or `market-id` when unsaid.

  Unknown ids fall back to the default rather than erroring, because these are
  read routes and a 404 on a market that simply has not been listed yet is a
  worse answer than the book the caller almost certainly wanted. `/markets`
  is what says which ids exist."
  [^js url]
  (let [m (some-> (.get (.-searchParams url) "m") (js/parseInt 10))]
    (if (and m (some #(= m (:id %)) markets)) m market-id)))

(defn- json [x status]
  (js/Response. (js/JSON.stringify (clj->js x))
                #js {:status status
                     :headers #js {"content-type" "application/json"
                                   "access-control-allow-origin" "*"}}))

(deftype ValidatorV2 [^js do-state ^js env]
  Object
  ;; Boot: the witness name, the signing key, and the replica. Everything is
  ;; in memory — this is a devnet and a validator that is evicted rejoins by
  ;; catching up, which is what inga.sync is for.
  ;; The options `r/replica` takes, in one place.
  ;;
  ;; `resume` needs exactly the same ones — the injected seams (hash-fn,
  ;; sign-fn, verify-fn, machine) are functions and a snapshot cannot carry
  ;; them, so the caller has to put them back. Two copies of this map would be
  ;; two definitions of what this chain IS: a different hash-fn or a different
  ;; machine on the resume path is a replica that agrees on the order and
  ;; disagrees on the result, which every number about it would hide.
  (replicaOpts [this name]
                         {:witness name
                                     :witnesses witnesses
                                     :quorum (c/quorum-size (count witnesses))
                                     ;; Identity is a CID. `block-hash` is
                                     ;; kept for the one thing it still does
                                     ;; — see its docstring — and no longer
                                     ;; decides what a block IS.
                                     :hash-fn block-cid
                                     ;; Two-chain on the v2 genesis set only.
                                     ;;
                                     ;; It is safe because a proposal may not
                                     ;; claim a round further than one past its
                                     ;; parent's without a quorum of new-views
                                     ;; — measured on inga's socket harness
                                     ;; with a Byzantine validator and a forger
                                     ;; inside the set. The first deployment
                                     ;; keeps the three-chain rule because a
                                     ;; commit rule is not something to change
                                     ;; under a chain that cannot be given the
                                     ;; code that changes it.
                                     ;; Two-chain on the v2 genesis set.
                                     ;;
                                     ;; It stalled this chain once, and the
                                     ;; stall was the DEPLOY, not the rule: a
                                     ;; restart brought four replicas back
                                     ;; from checkpoints 100 apart, 2 against
                                     ;; 2, and nothing after the split could
                                     ;; ever be certified. `/reset` on all four
                                     ;; recovered it. Retried here with that
                                     ;; recovery path in hand.
                                     :commit-rule :three-chain
                                     :chain-id chain-id
                                     ;; No special case for our own witness.
                                     ;; Trusting itself let the UNSIGNED copy
                                     ;; of its own vote into the record — the
                                     ;; one folded when the vote is produced,
                                     ;; before this Worker has signed it — so
                                     ;; the certificate named three witnesses
                                     ;; and carried two signatures: fine
                                     ;; locally, unverifiable by anyone else,
                                     ;; and a replica that had fallen behind
                                     ;; could never accept it. Dropping it
                                     ;; costs nothing, because the signed copy
                                     ;; is folded back as soon as dispatch has
                                     ;; signed it. A certificate is only worth
                                     ;; what a peer can check.
                                     :verify-fn (fn [w payload sig]
                                                  (.verifyCached this w payload sig))
                                     :machine
                                     {:init-fn genesis
                                      :apply-fn
                                      (fn [ex block]
                                        (-> (st/apply-block
                                         ex {:height (:inga.block/height block)
                                             :ts (:inga.block/ts block)
                                             :txs (mapv decode-tx
                                                        (:inga.block/proposals block))}
                                         {:chain-id chain-id
                                          :verify-fn
                                          (fn [pk payload sig]
                                            (true? (aget (or (.-txok this) #js {})
                                                         (str pk "|" payload "|" sig))))
                                          :derive-account addr/derive})
                                            ;; The tape lives INSIDE the
                                            ;; machine, so every replica
                                            ;; derives the same one from the
                                            ;; same blocks. The sequencer
                                            ;; keeps its tape beside the
                                            ;; engine, which is fine when
                                            ;; there is one writer and would
                                            ;; be four different tapes here.
                                            ;; Candles, in the machine for the
                                            ;; same reason the tape is: every
                                            ;; replica must derive the same
                                            ;; ones from the same blocks. Kept
                                            ;; beside it rather than folded
                                            ;; from it, because the tape is
                                            ;; bounded by COUNT — 200 fills is
                                            ;; a few dozen blocks on a busy
                                            ;; book, and a chart drawn from it
                                            ;; cannot see further back than
                                            ;; that no matter what it asks for.
                                            ;;
                                            ;; The fold is
                                            ;; `torihiki-chart.candle/absorb`,
                                            ;; the same call the terminal
                                            ;; draws with. Two folds that are
                                            ;; supposed to agree eventually
                                            ;; will not, and when the chart
                                            ;; and the chain disagree there is
                                            ;; no way to say which is lying.
                                            ;;
                                            ;; NOT in the state root: like the
                                            ;; tape, this is a view over the
                                            ;; fills the root already commits
                                            ;; to, and putting a derived index
                                            ;; under the hash would make every
                                            ;; change to how it is derived a
                                            ;; change to the chain's identity.
                                            (as-> ex'
                                                  ;; The guard asks EVERY book.
                                                  ;;
                                                  ;; It asked market 1's, so a
                                                  ;; block whose only fills were
                                                  ;; on market 2 skipped the
                                                  ;; fold entirely — the inner
                                                  ;; loop was per market and
                                                  ;; never got to run. Making
                                                  ;; the body multi-market and
                                                  ;; leaving the guard single
                                                  ;; is the same bug one level
                                                  ;; out, and it reads as
                                                  ;; correct.
                                                  (let [fills (mapcat #(bk/fills (get-in ex' [:books %]))
                                                                      (keys (:books ex')))]
                                                    (if (empty? fills)
                                                      ex'
                                                      (update ex' :candles
                                                              (fn [cs]
                                                                ;; Per market. This folded one book's
                                                                ;; fills into one vector, which was
                                                                ;; right while there was one market
                                                                ;; and silently gave every later
                                                                ;; market an empty chart forever.
                                                                ;;
                                                                ;; A vector here is a checkpoint from
                                                                ;; before the split; it is market 1's,
                                                                ;; because market 1 is what it could
                                                                ;; have been about.
                                                                (let [cs (if (vector? cs) {market-id cs} (or cs {}))]
                                                                  (reduce
                                                                   (fn [acc m]
                                                                     (let [fs (bk/fills (get-in ex' [:books m]))]
                                                                       (if (empty? fs)
                                                                         acc
                                                                         (let [c (reduce
                                                                                  (fn [c f]
                                                                                    (cndl/absorb
                                                                                     c {:level (:level f) :qty (:qty f)
                                                                                        :side (cndl/normalize-side
                                                                                               (:taker-side f))}))
                                                                                  nil fs)]
                                                                           (update acc m
                                                                                   (fn [v]
                                                                                     (into []
                                                                                           (take-last
                                                                                            candle-retention
                                                                                            (conj (or v [])
                                                                                                  (assoc c :h (:inga.block/height block)))))))))))
                                                                   cs
                                                                   (sort (keys (:books ex'))))))))))
                                            (as-> ex'
                                                  (update ex' :tape
                                                          (fn [t]
                                                            ;; Every market, and WHO traded.
                                                            ;;
                                                            ;; It read one book, so a second market's
                                                            ;; prints never existed. And it kept only
                                                            ;; price, size and side — so `/fills` for
                                                            ;; an account could not be answered from
                                                            ;; it at all, which is the first thing a
                                                            ;; trader asks for.
                                                            (into []
                                                                  (take-last
                                                                   tape-retention
                                                                   (concat
                                                                    (or t [])
                                                                    (mapcat
                                                                     (fn [m]
                                                                       (map (fn [f]
                                                                              {:m m
                                                                               :level (:level f)
                                                                               :qty (:qty f)
                                                                               :side (:taker-side f)
                                                                               :taker (:taker-owner f)
                                                                               :maker (:maker-owner f)
                                                                               :h (:inga.block/height block)})
                                                                            (bk/fills (get-in ex' [:books m]))))
                                                                     (sort (keys (:books ex'))))))))))
                                            ;; apply-block resets :rejected
                                            ;; every block, so a fold ends
                                            ;; holding only the last one's —
                                            ;; which reads as nothing ever
                                            ;; having been refused.
                                            (as-> ex' (update ex' :refused
                                                              (fnil into [])
                                                              (map :reason
                                                                   (:rejected ex'))))))
                                      :root-fn st/state-root}})

  ;; The bridge's keypair, derived and cached.
  ;;
  ;; Derived from the chain id and a fixed name, exactly like the witness keys
  ;; above and for the same reason: every replica needs the same one, and a
  ;; generated key would make each object a different bridge. The same devnet
  ;; caveat applies and is written down rather than hidden — anybody who knows
  ;; the chain id can compute this key and mint. It is a devnet whose
  ;; collateral is minted on request by design; what changed is that the
  ;; minting now has ONE issuer whose signature is on every unit, rather than
  ;; none at all.
  ;;
  ;; A real deployment replaces this with a key it does not derive, and
  ;; nothing else here changes.
  (faucetKey [this]
    (if (.-fkp this)
      (js/Promise.resolve (.-fkp this))
      (-> (secret-keypair env bridge-name)
          (.then (fn [^js k]
                   (when (not= bridge-pubkey (.-pub k))
                     (throw (js/Error. "bridge key mismatch — the secret is not the one in genesis")))
                   (set! (.-fkp this) k)
                   k)))))

  ;; Every witness's account id, computed locally.
  ;;
  ;; Each replica derives all four rather than asking, because genesis must be
  ;; identical on all of them and a set assembled from what a replica happened
  ;; to have heard would not be. It is the same derivation each object already
  ;; runs for its own key.
  (publisherSet [this]
    ;; No crypto and no promise: the public keys are genesis data, so the
    ;; accounts are a pure function of the source every replica compiles.
    ;; They used to be derived, which meant four key imports at every boot to
    ;; recompute something that was already a constant.
    (js/Promise.resolve
     (do (reset! publisher-accounts
                 (set (map (fn [w] (addr/derive (get validator-keys w))) witnesses)))
         (reset! faucet-account (addr/derive bridge-pubkey))
         @publisher-accounts))) 

  ;; Sign a deposit as the bridge and put it into consensus.
  ;;
  ;; The transaction goes through `r/submit` like any other — it is not
  ;; applied here. A faucet that credited an account locally would be this
  ;; replica inventing collateral the other three never agreed to, which is
  ;; the failure the bridge exists to make impossible.
  (faucetGrant [this account]
    (-> (.faucetKey this)
        (.then (fn [^js k]
                 (let [ex (:machine-state (.-replica this))
                       bridge @faucet-account
                       ;; The chain's next nonce, or the one after the last we
                       ;; issued — whichever is further along.
                       ;;
                       ;; `expected-nonce` reads COMMITTED state, and a grant
                       ;; takes a block to commit. Two requests a second apart
                       ;; both read the same number, both sign it, and the
                       ;; second is refused `bad-nonce`. Measured on the
                       ;; deployed chain: `refused {bad-nonce 1}` after two
                       ;; faucet calls issued back to back.
                       ;;
                       ;; The bound is what stops this from becoming
                       ;; permanent. If a grant is lost rather than delayed,
                       ;; the chain's nonce never catches up, and issuing
                       ;; `(inc last)` forever would mean every future grant
                       ;; carries a nonce that can never be accepted — a
                       ;; faucet that is broken in exactly the way it was
                       ;; trying to avoid. Past the bound it stops issuing and
                       ;; says so, which is recoverable; the caller retries
                       ;; once blocks catch up.
                       ;; The chain's expected nonce.
                       ;;
                       ;; Counting from what was last SUBMITTED runs
                       ;; permanently ahead when a transaction fails
                       ;; authentication — it never consumes its nonce, and
                       ;; nonces are strictly sequential, so nothing after the
                       ;; gap can apply. Measured: every bridge transaction
                       ;; stopped landing, faucet included.
                       ;;
                       ;; The cost is that two grants asked for within a block
                       ;; both see this number and one is refused as
                       ;; `bad-nonce`. A caller that wants two waits for the
                       ;; first. Combining the two rules was tried and made it
                       ;; worse — see the commit that reverted it.
                       nonce (tauth/expected-nonce ex bridge)
                       tx {:tx :deposit :account bridge :credit account
                           :amount faucet-grant}
                       payload (tauth/signing-payload chain-id bridge nonce tx)]
                   (-> (js/crypto.subtle.sign #js {:name "Ed25519"}
                                              (.-privateKey k)
                                              (.encode (js/TextEncoder.) payload))
                       (.then (fn [sig]
                                (let [env {:tx tx :account bridge :nonce nonce
                                           :pubkey (.-pub k) :sig (b64 sig)}
                                      body (js/JSON.stringify (clj->js env))]
                                  ;; Cached as verified for the same reason a
                                  ;; replica caches its own vote: this Worker
                                  ;; produced the signature a moment ago with
                                  ;; the key it derived, and asking WebCrypto
                                  ;; again would be re-answering a question it
                                  ;; just answered.
                                  (aset (or (.-txok this) (set! (.-txok this) #js {}))
                                        (str (.-pub k) "|" payload "|" (b64 sig)) true)
                                  (set! (.-replica this) (r/submit (.-replica this) body))
                                  (set! (.-lastFaucetNonce this) nonce)
                                  {:queued true :bridge bridge :nonce nonce}))))))))) 

  ;; List every market this build knows about that the chain does not yet
  ;; have, signed by the bridge.
  ;;
  ;; Markets used to be genesis data, and a replica restores from a checkpoint
  ;; — so adding one to `markets` added it to a chain nobody was running. It
  ;; has to arrive as a transaction (`torihiki.state/apply-tx :list-market`),
  ;; and the only key here that the chain recognises as an authority is the
  ;; bridge's, which is also the right one: whoever may list may price, and
  ;; pricing is what margin reads.
  ;;
  ;; Anyone may ASK. What they cannot do is invent a market — the listable set
  ;; is `markets`, which is reviewed with the code, and a market already
  ;; listed is refused by the engine. Same shape as `/faucet`: an open door
  ;; onto a bounded, reviewed action.
  (listMissingMarkets [this]
    ;; Bring the chain's markets in line with this build: list what is absent,
    ;; and amend what is present and different.
    ;;
    ;; The amend half exists because the first version only listed. Market 1
    ;; and 2 were on the chain from before markets had names, so `/markets`
    ;; answered `symbol: null` while the build said `BTC-PERP` — config in the
    ;; source is not state on the chain, which is the same lesson listing
    ;; itself was added for.
    ;;
    ;; `:tick` and `:lot` are refused by the engine, so an amend cannot
    ;; reprice a resting order however wrong this build's opinion of them is.
    (let [ex (:machine-state (.-replica this))
          on-chain (:markets ex)
          missing (remove #(contains? on-chain (:id %)) markets)
          stale (filter (fn [m]
                          (when-let [cur (get on-chain (:id m))]
                            (not= (dissoc (assoc m :id (:id m)) :tick :lot)
                                  (select-keys cur (keys (dissoc m :tick :lot))))))
                        markets)
          ;; A market with no price can be traded and never margined — mark
          ;; zero — so opening one is part of bringing the chain in line with
          ;; the build, not a separate errand. The engine lets the authority do
          ;; this exactly once per market, and the condition closes behind it.
          ;; Only markets the chain ALREADY HAS.
          ;;
          ;; This filtered the build's markets, not the chain's, so a market
          ;; being listed for the first time got a price transaction in the
          ;; same batch — signed for a market that does not exist yet, which
          ;; `api/validate` refuses as `:unknown-market`. Nonces are strictly
          ;; sequential, so a guaranteed-invalid transaction sitting at
          ;; nonce N+1 is a wall every later bridge transaction queues behind.
          ;;
          ;; Measured: with a spot market in the build and not on the chain,
          ;; every bridge transaction stopped landing — the faucet included.
          ;; Removing the market from the build restored them. Pricing follows
          ;; listing by one sync rather than riding along with it.
          unpriced (filter #(and (contains? (:markets ex) (:id %))
                                 (zero? (get-in ex [:oracle (:id %)] 0)))
                           markets)
          work (concat (map (fn [m] [:list m]) missing)
                       (map (fn [m] [:amend m]) stale)
                       (map (fn [m] [:amend-tiers m]) (filter :fee-tiers stale))
                       (map (fn [m] [:price m]) unpriced))]
      (if (empty? work)
        (js/Promise.resolve {:listed [] :amended []
                             :note "the chain already matches this build"})
        (-> (.faucetKey this)
            (.then
             (fn [^js k]
               (let [bridge @faucet-account
                     ;; The chain's expected nonce, and nothing else.
                     ;;
                     ;; This took `max` with the last nonce SUBMITTED, and that
                     ;; is only right while every submission lands. A tx that
                     ;; fails authentication never consumes its nonce — so the
                     ;; four amends that came back `bad-signature` left the
                     ;; chain at 13 while this counter had moved to 14, and
                     ;; every later batch was signed for a nonce the chain
                     ;; would never reach. Nonces are strictly sequential, so
                     ;; the gap is permanent: measured live, 15 and 16 sat
                     ;; unappliable while the chain waited for 13.
                     ;;
                     ;; Resubmitting a nonce is safe and is the point — a
                     ;; nonce is single-use, so a duplicate either lands once
                     ;; or is refused as `bad-nonce`. Guessing ahead is what
                     ;; is not safe, and the faucet's own docstring says so.
                     start (tauth/expected-nonce (:machine-state (.-replica this)) bridge)]
                 (-> (js/Promise.all
                      (clj->js
                       (map-indexed
                        (fn [i [kind m]]
                          (let [nonce (+ start i)
                                tx (case kind
                                     :list {:tx :list-market :account bridge :market (:id m)
                                            :spec (dissoc m :id)
                                            :book-opts {:n-levels 4096 :cap 65536 :ev-cap 65536}}
                                     ;; Sent in TWO transactions, and the split
                                     ;; is a measurement.
                                     ;;
                                     ;; A flat amend (a symbol, a rate) landed.
                                     ;; The same amend carrying `:fee-tiers` —
                                     ;; a vector of maps — did not: not
                                     ;; refused, not counted, nonce never
                                     ;; consumed. Splitting the nested part out
                                     ;; makes the next chain state say which of
                                     ;; the two is the one that cannot travel.
                                     :amend {:tx :amend-market :account bridge :market (:id m)
                                             :spec (dissoc m :id :tick :lot :fee-tiers :margin-tiers)}
                                     :amend-tiers {:tx :amend-market :account bridge :market (:id m)
                                                   :spec (select-keys m [:fee-tiers])}
                                     ;; The same number genesis gives the first
                                     ;; market: a devnet has no feed, and two
                                     ;; markets opening at different prices
                                     ;; would imply one.
                                     :price {:tx :oracle :account bridge
                                             :market (:id m) :price 1000})
                                payload (tauth/signing-payload chain-id bridge nonce tx)]
                            (-> (js/crypto.subtle.sign #js {:name "Ed25519"}
                                                       (.-privateKey k)
                                                       (.encode (js/TextEncoder.) payload))
                                (.then (fn [sig]
                                         (let [env {:tx tx :account bridge :nonce nonce
                                                    :pubkey (.-pub k) :sig (b64 sig)}]
                                           (aset (or (.-txok this) (set! (.-txok this) #js {}))
                                                 (str (.-pub k) "|" payload "|" (b64 sig)) true)
                                           (set! (.-replica this)
                                                 (r/submit (.-replica this)
                                                           (js/JSON.stringify (clj->js env))))
                                           (set! (.-lastFaucetNonce this) nonce)
                                           {:kind kind :market (:id m) :nonce nonce}))))))
                        work)))
                     (.then (fn [rs]
                              (let [rs (vec (array-seq rs))]
                                {:listed (filterv #(= "list" (name (:kind %))) rs)
                                 :amended (filterv #(#{"amend" "amend-tiers"} (name (:kind %))) rs)
                                 :opened (filterv #(= "price" (name (:kind %))) rs)
                                 :bridge bridge})))))))))))

  (boot [this name]
    ;; Re-boots when the name it holds is not the name it is being addressed
    ;; by. A Durable Object keeps running the code and the state it started
    ;; with until it is evicted, so a deploy that fixes a naming bug does not
    ;; fix the objects already holding the wrong name — three validators went
    ;; on believing they were w1 across two deploys, and only self-healing on
    ;; the mismatch cleared it.
    (if (and (.-ready this) (= (.-witness this) name))
      (js/Promise.resolve nil)
      (-> (.publisherSet this)
          ;; BEFORE the storage read, because everything after it can reach
          ;; `genesis`, and `genesis` throws without the bridge account and
          ;; the publisher set. A boot order that works by accident is one
          ;; deploy away from not.
          (.then (fn [_] (.faucetKey this)))
          (.then (fn [_] (.get ^js (.-storage do-state) "key")))
          (.then (fn [stored]
                   ;; The key is PERSISTED, not regenerated.
                   ;;
                   ;; It was regenerated on every boot, and a Durable Object
                   ;; is evicted routinely — so a validator came back with a
                   ;; new identity while every peer still held the old public
                   ;; key, and every vote it sent verified as false. The
                   ;; symptom was three replicas holding two votes each,
                   ;; one short of quorum, with no error anywhere: signatures
                   ;; that do not verify are dropped silently, which is
                   ;; correct and gives you nothing to read.
                   ;;
                   ;; A validator whose identity does not survive a restart is
                   ;; not a validator.
                   ;; DERIVED FROM THE NAME, not generated.
                   ;;
                   ;; Persisting the keypair made an identity survive a
                   ;; restart, and it does — of the SAME Durable Object.
                   ;; Renaming the object (which every `code-version` bump
                   ;; does, because that is how a self-rescheduling alarm is
                   ;; made to pick up a deploy at all) gives a fresh object
                   ;; with empty storage, so every replica came up with a new
                   ;; key while its peers held the old one. Fourteen votes
                   ;; dropped as `did-not-verify` at w3, two votes for its own
                   ;; tip, one short of quorum, forever. The votes were well
                   ;; formed and the signatures were real; they were the wrong
                   ;; key, and trust-on-first-use keeps the first one it saw.
                   ;;
                   ;; Two workarounds that undo each other: versioned names
                   ;; exist to defeat eviction, and defeat key agreement.
                   ;;
                   ;; Deriving the seed from chain-id and witness name breaks
                   ;; the tie — w3 is the same w3 in every incarnation, so
                   ;; there is nothing for first-use to get wrong. An Ed25519
                   ;; private key IS its 32-byte seed, and PKCS8 wraps it in a
                   ;; fixed 16-byte prefix, so this is an import rather than a
                   ;; key-generation scheme of my own.
                   ;;
                   ;; It is a DEVNET answer and a worse one than what it
                   ;; replaces in exactly one way: anybody who knows the
                   ;; chain-id and a witness name can compute that witness's
                   ;; private key. Real validators are given keys they
                   ;; generate themselves and publish in genesis. This is
                   ;; written down rather than hidden because the deployment
                   ;; is a devnet with mintable collateral and the alternative
                   ;; is a chain that cannot agree at all.
                   ;; NOW: the seed comes from a Worker secret, and the
                   ;; public half is in `validator-keys` where the review that
                   ;; approves a validator set can see it. Derivation is gone
                   ;; — see `keypair-from-seed` for why it is not kept as a
                   ;; fallback.
                   (secret-keypair env name)))
          (.then (fn [^js k]
                   (when-let [expect (get validator-keys name)]
                     (when (not= expect (.-pub k))
                       ;; The secret and the genesis set disagree. Refusing is
                       ;; the only safe answer: this object would sign as a
                       ;; witness its peers will not recognise, and every vote
                       ;; it sent would be dropped as `did-not-verify` — the
                       ;; failure that reads as a network problem and is not.
                       (throw (js/Error. (str "key mismatch for " name
                                              " — the secret is not the one in genesis")))))
                   (set! (.-kp this) k)
                   (set! (.-pub this) (.-pub k))
                   (set! (.-witness this) name)
                   ;; Its OWN key belongs in the key map.
                   ;;
                   ;; The map is filled from peers, and the loop that fills it
                   ;; skips self — correctly, since asking yourself for your
                   ;; own key is a round trip to nowhere. The consequence was
                   ;; not obvious: `verifyCerts` looks every signature's
                   ;; witness up HERE, finds nothing for itself, and skips the
                   ;; check; the synchronous verifier then answers from an
                   ;; empty cache, which means `false`.
                   ;;
                   ;; A replica votes on nearly every block of its own
                   ;; history, so nearly every certificate it is offered while
                   ;; catching up carries a signature it alone cannot check.
                   ;; Measured after `/wipe`: w4 was offered blocks 1..256,
                   ;; adopted none, `below-quorum` at height 4 with
                   ;; `per-witness {w1 true, w2 true, w4 false}` — it refused
                   ;; its own signature. **A replica could not rejoin a chain
                   ;; it had itself helped certify.**
                   ;;
                   ;; This is not the self-trust that was deliberately taken
                   ;; out of `verify-fn`: the signature is verified, with a
                   ;; public key, by WebCrypto. It is only that the key was
                   ;; missing from the place the verifier looks.
                   ;; Every validator's key, from genesis — not from
                   ;; whoever answered `/head` first. Trust-on-first-use was
                   ;; harmless while keys were derivable (there was nothing to
                   ;; impersonate) and is the weak point the moment they are
                   ;; real.
                   (set! (.-keys this)
                         (reduce (fn [o [w pub]] (doto o (aset w pub)))
                                 #js {} validator-keys))
                   (set! (.-verified this) #js {})
                   (set! (.-delivery this) #js {})
                   (set! (.-why this) #js {})
                   (set! (.-replica this) (r/replica (.replicaOpts this name)))
                   (set! (.-persisted this) 0)
                   (set! (.-ready this) true)
                   ;; Where this object actually runs.
                   ;;
                   ;; Asked from INSIDE the object, because that is the only
                   ;; vantage that answers it: `request.cf.colo` on the way in
                   ;; names the edge nearest whoever called, which for these
                   ;; measurements is a laptop. A Durable Object's own
                   ;; outbound request leaves from where the object is.
                   ;;
                   ;; Fire and forget: nothing waits on it and a failure
                   ;; leaves the field nil, which reads as "not measured"
                   ;; rather than as a location.
                   (-> (js/fetch "https://cloudflare.com/cdn-cgi/trace")
                       (.then #(.text %))
                       (.then (fn [t]
                                (set! (.-colo this)
                                      (second (re-find #"colo=(\S+)" t)))))
                       (.catch (fn [_] nil)))
                   ;; The log is NOT replayed here.
                   ;;
                   ;; It was, in one `.list` with no limit and one `r/replay`
                   ;; over everything it returned, and that is what took the
                   ;; deployment down. A Durable Object gets a CPU budget per
                   ;; invocation; past a few hundred blocks the replay exceeds
                   ;; it, and Cloudflare's answer to exceeding it is to RESET
                   ;; the object — which discards the in-memory state, so the
                   ;; next invocation boots and replays from zero and exceeds
                   ;; it again, at the same block, forever.
                   ;;
                   ;; Measured, not theorised: `wrangler tail` on the live
                   ;; validator showed 70 `exceeded its CPU time limit and was
                   ;; reset` and 124 `overloaded — requests queued for too
                   ;; long` in ninety seconds, every request 500, on a chain
                   ;; that had reached height ~455.
                   ;;
                   ;; The failure is worse than a slow start: it is a crash
                   ;; loop that CANNOT end on its own, because recovery is the
                   ;; very work that kills it. Nothing in the object gets to
                   ;; run, including the route that exists to wipe it.
                   ;;
                   ;; So boot leaves the replica at genesis and `catchUp`
                   ;; folds the log a bounded page at a time, across as many
                   ;; invocations as it takes. `r/replay` is a `reduce` over
                   ;; blocks sorted by height, so N pages in ascending order
                   ;; are exactly one call over all of them.
                   (set! (.-replayCursor this) "")
                   (set! (.-caughtUp this) false)
                   (set! (.-lastCkpt this) -1)
                   (set! (.-bootedFromCkpt this) false)
                   ;; RETURNED. This was fired and forgotten, and a Durable
                   ;; Object may be put to sleep as soon as the handler
                   ;; resolves — so the very first alarm was lost and the loop
                   ;; never started. The chain then only moved when something
                   ;; POSTed /step, which looked like the alarm firing and
                   ;; doing nothing rather than never firing at all.
                   (-> (.put ^js (.-storage do-state) "witness" name)
                       ;; Start from the newest checkpoint, so `catchUp` folds
                       ;; the tail above it instead of the whole log.
                       ;;
                       ;; Paging the replay bounded one invocation; it did not
                       ;; bound how many invocations there are, and that is
                       ;; what took the chain down again on 2026-08-04 during
                       ;; the inga migration: a deploy evicted all four
                       ;; replicas at once, each began folding two thousand
                       ;; blocks, and w1 went 449 -> 124 as it was reset and
                       ;; restarted before it could finish. The devnet had to
                       ;; be wiped. A checkpoint is what makes a code change
                       ;; stop costing the chain.
                       (.then (fn [_] (.restoreCheckpoint this)))
                       (.then (fn [_]
                                (.setAlarm ^js (.-storage do-state)
                                           (+ (js/Date.now) tick-ms))))))))))

  ;; Adopt the newest checkpoint, if there is one that parses.
  ;;
  ;; A checkpoint that does not parse is skipped rather than thrown from, and
  ;; the next one back is tried. That is why `checkpoint-kept` is two: a write
  ;; interrupted by a reset leaves a key that reads back as half an EDN form,
  ;; and a replica that died on it would be a replica whose recovery mechanism
  ;; is also its failure mode — which is exactly the shape of the bug this
  ;; whole line of work exists to remove.
  (restoreCheckpoint [this]
    (-> (.list ^js (.-storage do-state) #js {:prefix "snap:" :reverse true
                                             :limit checkpoints-kept})
        (.then (fn [^js entries]
                 (let [vals (js/Array.from (.values entries))
                       ks (js/Array.from (.keys entries))]
                   (loop [i 0]
                     (if (>= i (alength vals))
                       nil
                       (let [restored
                             (try
                               (let [snap (tsnap/read-string* (aget vals i))
                                     views (:views snap)
                                     snap (-> snap
                                              (update :machine-state tsnap/restore)
                                              ;; `merge` and not `assoc`: a
                                              ;; checkpoint written before
                                              ;; `:views` existed has none,
                                              ;; and restoring nil over a
                                              ;; freshly built machine would
                                              ;; be the same loss with an
                                              ;; extra step.
                                              (cond-> views
                                                (update :machine-state merge views)))]
                                 (r/resume (.replicaOpts this (.-witness this)) snap))
                               (catch :default e (.note! this e) nil))]
                         (if restored
                           (do (set! (.-replica this) restored)
                               (set! (.-lastCkpt this) (r/height restored))
                               (set! (.-bootedFromCkpt this) true)
                               ;; Everything at or below the checkpoint is
                               ;; already folded into it.
                               (set! (.-replayCursor this)
                                     (str "blk:" (subs (aget ks i) (count "snap:"))))
                               (set! (.-persisted this) (count (:chain restored)))
                               true)
                           (recur (inc i)))))))))
        (.catch (fn [e] (.note! this e) nil))))

  ;; Delete one bounded page of this object's storage, and say what is left.
  ;;
  ;; `.delete` takes at most 128 keys, so a page is 128 keys and one call
  ;; walks a few pages inside a wall-clock budget well under the limit that
  ;; resets the object. Keys are re-listed from the front each page because
  ;; the ones just deleted are gone — no cursor to keep, and nothing to lose
  ;; if the object is reset between calls.
  (wipePage [this]
    (let [deadline (+ (js/Date.now) 2000)
          step (fn step [n]
                 (-> (.list ^js (.-storage do-state) #js {:limit 128})
                     (.then (fn [^js entries]
                              (let [ks (js/Array.from (.keys entries))]
                                (if (zero? (alength ks))
                                  (js/Promise.resolve [n true])
                                  (-> (.delete ^js (.-storage do-state) ks)
                                      (.then (fn [_]
                                               (let [n' (+ n (alength ks))]
                                                 (if (< (js/Date.now) deadline)
                                                   (step n')
                                                   (js/Promise.resolve [n' false]))))))))))))]
      (-> (step 0)
          (.then (fn [[n done?]]
                   (set! (.-ready this) false)
                   (set! (.-witness this) nil)
                   (set! (.-persisted this) 0)
                   (set! (.-replayCursor this) "")
                   (set! (.-caughtUp this) false)
                   (json {:ok true :deleted n :drained done?
                          :note (if done?
                                  "empty — boots at genesis on the next request"
                                  "more remains, call /wipe again")}
                         200))))))

  ;; Fold one bounded page of the persisted log. Resolves true when the
  ;; replica has caught up to what storage holds.
  ;;
  ;; `blk:` keys are the height zero-padded to twelve digits, so lexicographic
  ;; order IS height order and `startAfter` is a correct cursor. That padding
  ;; was already there; without it `blk:10` would sort before `blk:2` and
  ;; paging would fold the chain out of order.
  ;;
  ;; A short page means the end: `.list` returns fewer than `limit` only when
  ;; there is nothing more under the prefix.
  (catchUp [this]
    (if (.-caughtUp this)
      (js/Promise.resolve true)
      (let [c (or (.-replayCursor this) "")
            opts (if (seq c)
                   #js {:prefix "blk:" :limit replay-page :startAfter c}
                   #js {:prefix "blk:" :limit replay-page})]
        (-> (.list ^js (.-storage do-state) opts)
            (.then (fn [^js entries]
                     (let [ks (js/Array.from (.keys entries))
                           n (alength ks)
                           blocks (keep (fn [raw]
                                          (let [[m _] (wire/decode
                                                       (js->clj (js/JSON.parse raw)))]
                                            (:block m)))
                                        (js/Array.from (.values entries)))]
                       (when (seq blocks)
                         (set! (.-replica this)
                               (r/replay (.-replica this) (vec blocks)))
                         (set! (.-persisted this)
                               (count (:chain (.-replica this)))))
                       (if (< n replay-page)
                         (do (set! (.-caughtUp this) true) true)
                         (do (set! (.-replayCursor this) (aget ks (dec n)))
                             false)))))))))

  ;; A synchronous answer from a cache the async fetch fills. Unknown key ->
  ;; false, never "unknown": a verifier that treats "I was not asked about
  ;; this" as acceptance turns a gap in bookkeeping into an accepted
  ;; signature.
  (verifyCached [this w payload sig]
    (true? (aget (or (.-verified this) #js {}) (str w "|" payload "|" sig))))

  (learnKeys [this]
    (js/Promise.all
     (clj->js
      ;; Only for keys we do not have.
      ;;
      ;; This asked for all three every time, and `ingest` calls it on every
      ;; inbound batch — so each arriving message cost three outbound HTTP
      ;; round trips, from every replica, to every other. The replicas
      ;; receiving the most traffic spent all their time on it and their own
      ;; alarms never got a turn: three of four sat at height zero, view zero,
      ;; having sent nothing at all while receiving four hundred messages. The
      ;; fourth was the leader, got the least traffic, and was the only one
      ;; ticking.
      ;;
      ;; A key that is wrong is still indistinguishable from a peer that is
      ;; silent, and re-asking is still the answer — but from the tick, on a
      ;; schedule, not from the path that runs once per message.
      ;; Nothing to ask for any more: `validator-keys` has them. Kept as a
      ;; loop over an empty set rather than deleted, because the SHAPE of
      ;; "learn the keys you do not have" is what a real validator set update
      ;; would use, and because deleting the field would hide that the map is
      ;; now filled at boot.
      (for [w witnesses :when (and (not= w (.-witness this))
                                   (not (aget (.-keys this) w)))]
        (-> (.fetch (.get ^js (.-VALIDATOR env)
                          (.idFromName ^js (.-VALIDATOR env) (do-name w)))
                    (str "https://v/head?w=" w))
            (.then #(.json %))
            (.then (fn [j] (when-let [k (aget j "pubkey")]
                             (aset (.-keys this) w k))))
            (.catch (fn [_] nil)))))))

  ;; Verify everything in a batch, cache the answers, then fold the batch
  ;; synchronously. The rules stay synchronous and the asynchrony stays at the
  ;; edge — the shape `inga.attest/pending-checks` exists for.
  ;; One definition of "a batch arrived", whichever transport carried it.
  ;;
  ;; `/msg` had this inline. A socket delivering the same bytes to a different
  ;; entry point would have been a second copy of the decode, the counters and
  ;; the failure handling — and two definitions of what arriving means is how
  ;; a transport ends up quietly counting differently from the one it
  ;; replaces.
  (ingestBody [this text]
    (try
      (let [body (js/JSON.parse text)
            raw (js->clj (aget body "msgs"))
            msgs (keep (fn [m] (first (wire/decode m))) raw)]
        (set! (.-msgs-in this) (+ (or (.-msgs-in this) 0) (count msgs)))
        (set! (.-types this) (or (.-types this) #js {}))
        (doseq [m msgs]
          (let [k (name (:type m))]
            (aset (.-types this) k (inc (or (aget (.-types this) k) 0)))))
        (-> (.ingest this (vec msgs))
            (.then (fn [_] (count msgs)))))
      (catch :default _ (js/Promise.resolve nil))))

  (ingest [this msgs]
    ;; Learn the keys FIRST. A message that arrived before its sender's key
    ;; was known could not be verified, so it was dropped — and a dropped
    ;; message is not retried, because the sender has no idea it was lost.
    ;; Keys were learned at the top of a tick, so every vote that arrived in
    ;; the gap between ticks was thrown away, which over HTTP is most of them.
    ;; The chain reached height one and stopped with a single vote recorded.
    (-> (.learnKeys this)
        (.then (fn [_] (.ingest2 this msgs)))))

  (verifyTx [this env]
    ;; One envelope, verified and cached. Used both when a transaction is
    ;; submitted here and when it arrives inside somebody's proposal.
    (set! (.-txok this) (or (.-txok this) #js {}))
    (let [payload (tauth/signing-payload chain-id (:account env)
                                         (:nonce env) (:tx env))
          k (str (:pubkey env) "|" payload "|" (:sig env))]
      (-> (js/crypto.subtle.importKey "raw" (b64-> (:pubkey env))
                                      #js {:name "Ed25519"} false #js ["verify"])
          (.then (fn [pk] (js/crypto.subtle.verify
                           #js {:name "Ed25519"} pk (b64-> (:sig env))
                           (.encode (js/TextEncoder.) payload))))
          (.then (fn [ok] (aset (.-txok this) k (true? ok)) (true? ok)))
          (.catch (fn [_] false)))))

  (verifyTxs [this msgs]
    ;; Every transaction carried by every proposal in this batch. Verified
    ;; here because the seam that consumes the answer is synchronous and the
    ;; platform verifier is not; the three-chain rule leaves two blocks of
    ;; room between a proposal arriving and the block committing.
    (set! (.-txok this) (or (.-txok this) #js {}))
    ;; The seq is realised INSIDE this call, so anything that throws while
    ;; building it throws synchronously out of here rather than rejecting a
    ;; promise — and the whole batch of votes goes with it, silently, because
    ;; the sender never learns its message was dropped. Wrapped so a bad
    ;; transaction cannot cost the consensus messages it was travelling with.
    (try
    (js/Promise.all
     (clj->js
      (for [m msgs
            :when (= :proposal (:type m))
            raw (:inga.block/proposals (:block m))
            :let [env (try (decode-tx raw) (catch :default _ nil))]
            :when (and env (:pubkey env) (:sig env) (integer? (:account env)))
            ]
        (.verifyTx this env))))
      (catch :default e (.note! this e) (js/Promise.resolve nil))))

  (ingest2 [this msgs]
    ;; Timed per phase, because the hop is where the median block time is.
    ;;
    ;; The tick was decomposed and cleared: its work is capped at 40 ms and
    ;; the transport under it is 2-4 ms. Flushing per batch instead of per
    ;; tick took the block-time TAIL from 3443 ms to 392 and left the median
    ;; at ~250. `propose-refusal` never once said `too-soon`, so the 100 ms
    ;; block interval is not it either. That leaves what a replica does with
    ;; a message after it arrives, which nothing had ever timed.
    (let [t0 (js/Date.now) mark (volatile! [])
          phase (fn [nm] (fn [x] (vswap! mark conj [nm (- (js/Date.now) t0)]) x))]
      (-> (.verifyTxs this msgs)
          (.then (phase "verifyTxs"))
          (.then (fn [_] (.verifyCerts this msgs)))
          (.then (phase "verifyCerts"))
          (.then (fn [_] (.answerSyncRequests this msgs)))
          (.then (phase "answerSync"))
          ;; `:sync-request` never reaches the replica: this object answers it
          ;; and `inga.replica` cannot.
          (.then (fn [_] (.foldMsgs this (remove #(= :sync-request (:type %)) msgs))))
          (.then (phase "foldMsgs"))
          (.then (fn [r]
                   (set! (.-ingestPhases this) (clj->js @mark))
                   (set! (.-ingestMs this)
                         (let [a (or (.-ingestMs this) #js [])]
                           (.slice (.concat a (- (js/Date.now) t0)) -64)))
                   r)))))

  ;; Verify the certificates carried INSIDE a sync response, before the
  ;; replica is asked whether it may adopt them.
  ;;
  ;; The verifier this replica hands `inga` is synchronous — it can only be,
  ;; because `validate-segment` is a pure function — so it answers out of
  ;; `.-verified`, a cache filled by the ASYNC WebCrypto verification of votes
  ;; and new-views as they arrive. A certificate that arrives inside a block
  ;; from a peer was never seen here as votes, so every signature in it is a
  ;; cache miss, and a cache miss is `false`.
  ;;
  ;; That is why catching up failed with `:below-quorum` on segments that were
  ;; perfectly good: 256 blocks offered, 0 adopted, and the certificates being
  ;; refused were ones a quorum had actually signed. `verifyTxs` already
  ;; solved this exact problem for transactions; certificates needed the same
  ;; treatment and did not have it.
  ;;
  ;; `att/pending-checks` produces the `[witness payload sig]` triples the
  ;; certificate needs checked, so this does not reimplement the payload
  ;; format — the same reason `client.cljs` calls `torihiki.auth` rather than
  ;; spelling the signing payload out by hand.
  (verifyCerts [this msgs]
    (set! (.-verified this) (or (.-verified this) #js {}))
    (let [;; Every certificate that ARRIVES inside a message, from either
          ;; place it can arrive.
          ;;
          ;; `:sync-response` was fixed first, and the same hole was left in
          ;; `:new-view` — where it is worse. `inga.replica/handle-new-view`
          ;; verifies the `high-qc` a new-view carries, with this same
          ;; synchronous cache, and REJECTS the whole message when it does not
          ;; verify. A replica that just restarted has seen none of the votes
          ;; in that certificate, so every check is a cache miss, so every
          ;; new-view is dropped — and `sync-view`, the thing that exists to
          ;; pull a drifted replica back, is only reached by a new-view that
          ;; was accepted.
          ;;
          ;; Measured: w4 restarted, received 212 new-views, verified 216
          ;; signatures, and sat at view 16 while its peers passed 92 —
          ;; advancing one view per timeout, alone, forever. It was the leader,
          ;; so the chain stopped at height 2. That is the shape of every halt
          ;; recorded in S14; this is why they happened.
          certs (concat
                 (for [m msgs :when (= :sync-response (:type m))
                       b (:blocks m) :let [j (:inga.block/justify b)] :when j] j)
                 (for [m msgs :when (= :new-view (:type m))
                       :let [j (:high-qc m)] :when j] j))
          checks (for [j certs
                       [w payload sig] (att/pending-checks j chain-id)
                       :let [pk (aget (.-keys this) (wire/wire-id w))]
                       :when (and pk payload sig)]
                   [w payload sig pk])]
      (if (empty? checks)
        (js/Promise.resolve nil)
        (-> (js/Promise.all
             (clj->js
              (for [[w payload sig pk] checks]
                (-> (js/crypto.subtle.importKey "raw" (b64-> pk) #js {:name "Ed25519"}
                                                false #js ["verify"])
                    (.then (fn [k] (js/crypto.subtle.verify
                                    #js {:name "Ed25519"} k (b64-> sig)
                                    (.encode (js/TextEncoder.) payload))))
                    (.then (fn [ok]
                             (aset (.-verified this) (str (wire/wire-id w) "|" payload "|" sig)
                                   (true? ok))
                             nil))
                    (.catch (fn [_] nil))))))
            (.then (fn [_] nil))))))

  ;; Answer a peer's sync request from STORAGE, not from memory.
  ;;
  ;; `inga.replica/handle-sync-request` serves `(:chain state)`, and since
  ;; `resume` bounds that chain to its last few blocks, a replica booted from a
  ;; checkpoint can serve almost nothing. Measured: a replica reset to genesis
  ;; sent 49 sync requests, its peers sent back 115 sync responses, and it
  ;; stayed at height 0 — every response was empty. **A replica that cannot
  ;; serve history cannot heal its peers**, and the bounded resume that made
  ;; restarts cheap is what took that away.
  ;;
  ;; The blocks were never lost; they are `blk:` keys in this object's own
  ;; storage. What was missing is that consensus is a pure library and cannot
  ;; read them. So the read happens here, where the I/O already lives, and the
  ;; replica is handed a message type it no longer has to answer.
  ;;
  ;; Bounded by `inga.sync`'s own `:max-batch`, which is the same cap the
  ;; in-memory path applied — an unbounded range from a stranger is a request
  ;; that costs this object everything it holds.
  (answerSyncRequests [this msgs]
    (let [reqs (filter #(= :sync-request (:type %)) msgs)]
      (if (empty? reqs)
        (js/Promise.resolve nil)
        (let [cap (:max-batch isync/default-params)]
          (-> (js/Promise.all
               (clj->js
                (for [{:keys [from to witness]} reqs
                      :let [from (max 1 (or from 1))
                            to (max from (or to from))]]
                  (-> (.list ^js (.-storage do-state)
                             #js {:prefix "blk:"
                                  :start (blk-key from)
                                  :end (blk-key (inc to))
                                  :limit cap})
                      (.then (fn [^js entries]
                               {:to (or witness :all)
                                :blocks
                                (vec (keep (fn [raw]
                                             (let [[m _] (wire/decode
                                                          (js->clj (js/JSON.parse raw)))]
                                               (:block m)))
                                           (js/Array.from (.values entries))))}))
                      (.catch (fn [e] (.note! this e) {:to :all :blocks []}))))))
              (.then (fn [^js batches]
                       (let [out (for [{:keys [to blocks]} (js/Array.from batches)
                                       :when (seq blocks)]
                                   {:to to
                                    :msg {:type :sync-response :blocks blocks}})]
                         (when (seq out) (.queue! this (vec out)))
                         nil))))))))

  (foldMsgs [this msgs]
    (-> (js/Promise.all
         (clj->js
          (for [m msgs
                :let [w (:witness m) sig (:sig m)]
                :when (and w sig)
                :let [payload (case (:type m)
                                :vote (att/vote-payload chain-id (:view m) (:height m)
                                                        (:block-hash m) w)
                                :new-view (att/new-view-payload chain-id (:view m) w
                                                                (:high-qc m))
                                nil)]
                :when payload
                :let [pk (aget (.-keys this) w)]]
            (if-not pk
              (js/Promise.resolve nil)
              (-> (js/crypto.subtle.importKey "raw" (b64-> pk) #js {:name "Ed25519"}
                                              false #js ["verify"])
                  (.then (fn [k] (js/crypto.subtle.verify
                                  #js {:name "Ed25519"} k (b64-> sig)
                                  (.encode (js/TextEncoder.) payload))))
                  (.then (fn [ok]
                           (aset (.-verified this) (str w "|" payload "|" sig)
                                 (true? ok))))
                  (.catch (fn [_] nil)))))))
        (.then (fn [_]
                 (let [out (reduce (fn [acc m]
                                     (let [[s' o] (r/on-message (.-replica this) m
                                                                (js/Date.now))]
                                       (set! (.-replica this) s')
                                       (into acc o)))
                                   [] msgs)]
                   ;; Sent at the END of the batch, once, and not awaited.
                   ;;
                   ;; This queued and let the tick send, which cost a tick
                   ;; wait on EVERY hop: a proposal waited for the receiver's
                   ;; alarm before its vote left, and the vote waited again
                   ;; before the certificate could turn into the next
                   ;; proposal. Two waits a block, at a measured tick gap of
                   ;; 33-43 ms with a tail past a second — against a round
                   ;; trip between replicas of 2-4 ms.
                   ;;
                   ;; The namespace docstring is right about what went wrong
                   ;; before and this is not that. Dispatching PER MESSAGE
                   ;; amplified — one in, three out, each of which produced
                   ;; three more inside the same invocation — until it hit the
                   ;; subrequest limit and the chain stopped after a dozen
                   ;; blocks. Here the whole batch is folded first and flushed
                   ;; once, so an invocation makes at most one POST per peer,
                   ;; exactly what a tick made. What changes is when, not how
                   ;; many.
                   ;;
                   ;; Not awaited, for the reason a tick no longer awaits it
                   ;; either: waiting on a peer to answer is how a replica
                   ;; hands its clock to the slowest one, and here it would
                   ;; also let two objects wait on each other.
                   ;; Which path produced the proposal.
                   ;;
                   ;; The block MINIMUM is 25-37 ms and the median 184-211,
                   ;; and the story for that gap has been "the event path does
                   ;; not fire every block, and the fallback is a 40 ms
                   ;; clock". That is an inference. These two counters are the
                   ;; fact: a proposal that leaves from here was driven by a
                   ;; message, one that leaves from `round` was driven by the
                   ;; alarm. If the alarm is producing most of them, the
                   ;; median has a cause in this code and not in the platform.
                   (when (some #(= :proposal (:type (:msg %))) out)
                     (set! (.-proposeOnMsg this) (inc (or (.-proposeOnMsg this) 0))))
                   (.queue! this out)
                   (.notify! this)
                   (let [sends (js/Promise.resolve (.flush! this))]
                     (when (fn? (.-waitUntil do-state)) (.waitUntil do-state sends))
                     (.catch sends (fn [_] nil)))
                   (.persist! this))))))

  ;; Persistence was turned OFF here for one deploy, to test whether writing a
  ;; `blk:` key per block was the height-225 ceiling. It is not: the chain
  ;; stopped at 225 with nothing being written. Restored, because restart
  ;; recovery is not optional and the experiment answered its question.
  ;; A checkpoint: the replica as data, so a restart does not have to fold the
  ;; log to get back to where it was.
  ;;
  ;; TWO snapshots compose here and each covers what the other cannot.
  ;; `inga.replica/snapshot` bounds the consensus state (a tail of the chain,
  ;; the certificates naming it, the pacemaker, and the `:voted-below`
  ;; watermark that keeps a resumed replica from voting twice). Its
  ;; `:machine-state` it carries whole — and this machine's state is a
  ;; `torihiki` exchange holding `Book` records backed by typed arrays, which
  ;; is not data and does not serialise. `torihiki.snapshot/capture` turns
  ;; that into plain data whose `canonical-bytes` are byte-identical on
  ;; restore, which is the only equality that matters: two states can be `=`
  ;; and encode differently, and it is the encoding a validator signs.
  (checkpoint! [this]
    (let [s (.-replica this)
          h (r/height s)]
      (if (or (zero? h)
              (pos? (mod h checkpoint-every))
              (= h (or (.-lastCkpt this) -1)))
        (js/Promise.resolve nil)
        (let [ms (:machine-state s)
              ;; The tape and the candles ride BESIDE the engine snapshot.
              ;;
              ;; `torihiki.snapshot/capture` takes the engine's canonical
              ;; state, which these are not — they are views the validator's
              ;; apply-fn folds alongside it. So they were dropped, and a
              ;; replica that restarted came back with an empty tape and no
              ;; candles, rebuilding only from blocks after the restart.
              ;;
              ;; Measured: three of four replicas served zero trades and zero
              ;; candles while the fourth served fourteen and twelve, on the
              ;; same chain, at the same height. The page reads w1 and w1
              ;; happened to be the one that had not restarted — the chart
              ;; worked by luck, and any eviction would have emptied it.
              ;;
              ;; Beside rather than inside: putting a view into the engine's
              ;; snapshot would make the engine's serialisation depend on what
              ;; a UI wanted to draw.
              snap (-> (r/snapshot s)
                       (update :machine-state tsnap/capture)
                       (assoc :views {:tape (vec (:tape ms))
                                      :candles (vec (take-last checkpointed-candles
                                                               (:candles ms)))}))]
          (-> (.put ^js (.-storage do-state) (ckpt-key h) (tsnap/write-string snap))
              (.then (fn [_]
                       (set! (.-lastCkpt this) h)
                       ;; Drop the ones past the keep count. `.list` with
                       ;; `reverse` gives newest first, so what to delete is
                       ;; whatever is left after taking the ones we keep.
                       (.list ^js (.-storage do-state)
                              #js {:prefix "snap:" :reverse true})))
              (.then (fn [^js entries]
                       (let [ks (js/Array.from (.keys entries))
                             stale (drop checkpoints-kept ks)]
                         (if (seq stale)
                           (.delete ^js (.-storage do-state) (clj->js (vec stale)))
                           (js/Promise.resolve 0)))))
              (.catch (fn [e]
                        ;; A checkpoint that fails is a slower restart, not a
                        ;; broken replica. It must never take the tick with it.
                        (.note! this e)
                        nil)))))))

  ;; ── state transfer ────────────────────────────────────────────────────────
  ;;
  ;; A replica whose EXECUTED state has diverged cannot be repaired by
  ;; consensus. `inga.sync` carries blocks, and blocks are what this replica
  ;; already agreed to — the disagreement is about what applying them produced.
  ;; Its own checkpoints are the divergent state written down. So the only
  ;; thing that fixes it is a state the others hold, and until now there was no
  ;; way to hand one over.
  ;;
  ;; Measured on the deployed chain: w1 disagreed with w2, w3 and w4 in 16 of
  ;; 16 strict same-height comparisons, holding a resting order at a price the
  ;; other three did not have. It kept voting and its votes kept counting —
  ;; consensus orders blocks and never asked what anybody computed — so the
  ;; chain ran on exactly the quorum, with the redundancy that quorum is for
  ;; already spent.
  ;;
  ;; ## Why a quorum and not a peer
  ;;
  ;; Adopting one peer's state makes that peer able to rewrite this one. The
  ;; snapshots are fetched from every other witness, each is RESTORED HERE and
  ;; its state root computed locally, and a group of them is adopted only if it
  ;; is at least `quorum-size` strong. That is the same 2f+1 the chain uses to
  ;; decide anything else, applied to a question consensus does not ask.
  ;;
  ;; The root is computed rather than believed: a peer reporting its own root
  ;; would be trusted about exactly the thing in dispute.
  ;; The manual override. Same evidence, same action; it just does not wait
  ;; for the interval.
  (adoptFromPeers [this]
    (-> (.adoptIfOutvoted this)
        (.then (fn [res] (json res (if (:ok res) 200 409))))
        (.catch (fn [e] (.note! this e)
                  (json {:ok false :reason (str (or (.-message e) e))} 500)))))

  ;; Notice, without being told.
  ;;
  ;; `/adopt` is a button, and a chain whose repair needs somebody to press a
  ;; button is a chain that stays broken for as long as nobody is looking. The
  ;; divergence this exists for ran for hours while the terminal displayed
  ;; `4 replicas, agreeing`.
  ;;
  ;; So the tick asks, at an interval: is there a checkpoint height I share
  ;; with every peer, do a quorum of them agree on a state root there, and is
  ;; mine different? Three yeses is the same evidence `/adopt` demands, reached
  ;; without anybody deciding to look.
  ;;
  ;; Rate-limited because the question costs three fetches and a restore, and
  ;; because a replica that is merely BEHIND will answer it wrongly for a
  ;; moment — it has no checkpoint at the shared height yet, which reads as
  ;; nothing to compare rather than as a disagreement, so the interval is what
  ;; keeps that from being asked hundreds of times a minute.
  (maybeAdopt! [this]
    (let [now (js/Date.now)
          due (or (.-nextAdoptCheck this) 0)]
      (if (< now due)
        (js/Promise.resolve nil)
        (do
          (set! (.-nextAdoptCheck this) (+ now adopt-check-ms))
          ;; `js/Promise.resolve` around it, because `adoptIfOutvoted` does not
          ;; always return a promise: the early refusals (`a-peer-did-not-answer`,
          ;; `peers-share-no-checkpoint-height`) return a map directly, and
          ;; `.then` on a map is not a function. It threw on exactly the paths
          ;; that were supposed to be the quiet ones, and the throw was caught
          ;; and recorded — which is how it was visible at all.
          (-> (js/Promise.resolve (.adoptIfOutvoted this))
              ;; EVERY answer is recorded, not only the ones that adopt.
              ;;
              ;; The first version stored successes and dropped the rest, so a
              ;; check that ran twice and refused twice was indistinguishable
              ;; from a check that never ran — which is the same shape as the
              ;; divergence it exists to catch, written into the thing catching
              ;; it. `/head` reports why it did not act, which is the only
              ;; question anybody asks of a repair that has not happened.
              (.then (fn [res]
                       (set! (.-adoptCheck this) (clj->js (or res {:ok false :reason "nil"})))
                       (when (:ok res)
                         (set! (.-adopted this) (clj->js res)))
                       res))
              (.catch (fn [e]
                        (.note! this e)
                        (set! (.-adoptCheck this)
                              #js {"ok" false "reason" (str "threw: " (or (.-message e) e))})
                        nil)))))))

  ;; The comparison, and the adoption if it is warranted. Returns a description
  ;; rather than a Response so both the tick and `/adopt` can use it — one
  ;; definition of what being outvoted means.
  (adoptIfOutvoted [this]
    (let [me (.-witness this)
          peers (remove #(= me %) witnesses)
          q (c/quorum-size (count witnesses))
          ask (fn [w suffix]
                (-> (.fetch (.get ^js (.-VALIDATOR env)
                                  (.idFromName ^js (.-VALIDATOR env) (do-name w)))
                            (str "https://v/snapshot?w=" w suffix))
                    (.then #(.json %))
                    (.then (fn [^js j] {:witness w :ok (aget j "ok")
                                        :heights (vec (array-seq (or (aget j "heights") #js [])))
                                        :edn (aget j "edn")}))
                    (.catch (fn [_] nil))))
          root-of (fn [edn]
                    (try (st/state-root (tsnap/restore (:machine-state (tsnap/read-string* edn))))
                         (catch :default _ nil)))]
      (-> (js/Promise.all (clj->js (for [w peers] (ask w ""))))
          (.then
           (fn [rs]
             (let [rs (remove nil? (array-seq rs))]
               (if (not= (count rs) (count peers))
                 (js/Promise.resolve {:ok false :reason "a-peer-did-not-answer"})
                 ;; The height the PEERS share, not the one everybody shares.
                 ;;
                 ;; This required a checkpoint height every replica held,
                 ;; including this one — and that is exactly backwards: a
                 ;; replica far enough behind to need repairing is the one
                 ;; whose window has stopped overlapping. Observed live: w2 sat
                 ;; at checkpoint 1307600 while the other three kept 1308100
                 ;; and up, so the intersection was empty and the check refused
                 ;; with `no-shared-checkpoint-height` — the one replica that
                 ;; needed the state was the reason nobody could be given it.
                 ;;
                 ;; With four replicas the three peers ARE the quorum, so what
                 ;; they agree on needs no vote from the replica adopting it.
                 (let [common (apply set/intersection (map (comp set :heights) rs))
                       h (when (seq common) (apply max common))]
                   (if (nil? h)
                     (js/Promise.resolve {:ok false :reason "peers-share-no-checkpoint-height"})
                     (-> (.list ^js (.-storage do-state)
                                #js {:prefix "snap:" :reverse true :limit checkpoints-kept})
                         (.then
                          (fn [^js mine]
                            (let [mk (js/Array.from (.keys mine))
                                  mv (js/Array.from (.values mine))
                                  my-h (mapv #(js/parseInt (subs % (count "snap:")) 10)
                                             (array-seq mk))
                                  i (first (keep-indexed #(when (= h %2) %1) my-h))
                                  ;; No checkpoint at their height means one of
                                  ;; two things and both want the same answer:
                                  ;; this replica is behind, or it evicted that
                                  ;; height while they kept it. `nil` compares
                                  ;; unequal to any root, so the adopt is taken.
                                  my-root (when i (root-of (aget mv i)))
                                  behind? (or (empty? my-h) (< (apply max my-h) h))]
                              (-> (js/Promise.all (clj->js (for [w peers] (ask w (str "&h=" h)))))
                                  (.then
                                   (fn [rs2]
                                     (let [scored (keep (fn [r]
                                                          (when (:ok r)
                                                            (when-let [rt (root-of (:edn r))]
                                                              (assoc r :root rt))))
                                                        (remove nil? (array-seq rs2)))
                                           [root g] (last (sort-by (comp count val)
                                                                   (group-by :root scored)))]
                                       (cond
                                         (or (nil? g) (< (count g) q))
                                         {:ok false :reason "no-quorum-agreed-on-a-state"
                                          :height h :quorum q
                                          :offered (mapv #(select-keys % [:witness :root]) scored)}

                                         (and my-root (= root my-root))
                                         {:ok false :reason "already-agrees" :height h :root root}

                                         :else
                                         (let [edn (:edn (first g))
                                               snap (tsnap/read-string* edn)
                                               views (:views snap)
                                               ;; **Never lower the vote
                                               ;; watermark.**
                                               ;;
                                               ;; `inga.replica/snapshot` puts
                                               ;; `:voted-below` at the tip and
                                               ;; says what it is for: refusing
                                               ;; MORE costs a vote at a height
                                               ;; already decided, refusing
                                               ;; LESS is equivocation. A peer's
                                               ;; snapshot carries the peer's
                                               ;; watermark, so adopting it
                                               ;; wholesale hands this replica
                                               ;; permission to vote again at
                                               ;; every height between the two
                                               ;; — and it did: w3 and w4
                                               ;; reported `equivocators [w1]`
                                               ;; persistently after an
                                               ;; adoption.
                                               ;;
                                               ;; Equivocation is the one thing
                                               ;; this chain slashes for. A
                                               ;; repair that causes it is
                                               ;; worse than the divergence it
                                               ;; repairs.
                                               mine (r/height (.-replica this))
                                               snap (-> snap
                                                        (update :machine-state tsnap/restore)
                                                        (cond-> views (update :machine-state merge views))
                                                        (update :voted-below (fnil max 0) mine))
                                               resumed (r/resume (.replicaOpts this me) snap)]
                                           (set! (.-replica this) resumed)
                                           (set! (.-notifiedAt this) nil)
                                           (set! (.-adopted this)
                                                 #js {"height" h "from" (clj->js (mapv :witness g))
                                                      "was" (or my-root "none") "now" root
                                                      "behind" behind?})
                                           (-> (.put ^js (.-storage do-state) (ckpt-key h) edn)
                                               (.then (fn [_]
                                                        (set! (.-lastCkpt this) h)
                                                        (set! (.-bootedFromCkpt this) true)
                                                        (set! (.-replayCursor this) (blk-key h))
                                                        (set! (.-persisted this) (count (:chain resumed)))
                                                        {:ok true :height h
                                                         :adopted-from (mapv :witness g)
                                                         :root-was (or my-root "none") :root-now root
                                                         :behind behind?}))))))))))))))))))))))
  ;; No `.catch` here. The paren juggling that closed this form put one on a
  ;; value that is not a promise, and it threw `.catch is not a function` on
  ;; every quiet path — the refusals, which are the common case. Both callers
  ;; catch: `maybeAdopt!` records the throw and `adoptFromPeers` turns it into
  ;; a 500. A third catch was never the thing keeping this safe. 


  (persist! [this]
    ;; Everything adopted since the last write. Cheap because the chain only
    ;; grows: a block that is in storage is a block this replica already
    ;; decided to keep.
    (let [chain (:chain (.-replica this))
          from (or (.-persisted this) 0)
          new (subvec chain (min from (count chain)))]
      (if (empty? new)
        (js/Promise.resolve nil)
        (-> (js/Promise.all
             (clj->js
              (for [b new]
                (.put ^js (.-storage do-state)
                      (blk-key (:inga.block/height b))
                      (js/JSON.stringify (clj->js (wire/encode {:type :proposal
                                                                :block b})))))))
            (.then (fn [_] (set! (.-persisted this) (count chain)) nil))))))

  (queue! [this outbox]
    (set! (.-outq this) (into (vec (or (.-outq this) [])) outbox))
    nil)

  (flush! [this]
    (let [q (vec (or (.-outq this) []))]
      (set! (.-outq this) [])
      (.dispatch this q)))

  ;; A standing socket to a peer, or nil while there is not one yet.
  ;;
  ;; Every message between replicas was its own HTTP POST to a Durable Object
  ;; stub: a request to route, a handler invocation to start, a response to
  ;; wait for. The round trip measured 2-4 ms in the same colo and the median
  ;; block still took 160-195 ms, with delivery, ingest, the block interval
  ;; and the proposal path each measured and each ruled out. What was left
  ;; was the shape of the transport itself — a request per message.
  ;;
  ;; A socket makes a message an EVENT at the far end instead of a request.
  ;; One connection per ordered pair, opened lazily and one-way: replies come
  ;; back over the peer's own socket to us, so neither side reads.
  ;;
  ;; Returns nil rather than a promise while it opens, and `dispatch` posts in
  ;; the meantime — the socket is an optimisation and the POST is the
  ;; contract. A transport that has to be up for the chain to run is a second
  ;; way for the chain to stop.
  (peerSocket [this w]
    (let [m (or (.-wsout this) (do (set! (.-wsout this) #js {}) (.-wsout this)))
          ws (aget m w)]
      (cond
        ;; OPEN **and answered**. A socket is used only after the far end has
        ;; said something back over it.
        ;;
        ;; Without that this shipped an outage: the receive handler dropped
        ;; every message (it read the tags off `ctx`, which is not where the
        ;; state lives here), the senders saw a healthy open socket, and the
        ;; chain stopped at 15222 with `delivery {w2:ws 7, w3:ws 6}` and
        ;; nothing arriving. A transport that cannot tell "connected" from
        ;; "listened to" turns a receive bug into a silent partition.
        (and ws (= 1 (.-readyState ws)) (aget (or (.-wsok this) #js {}) w)) ws
        (and ws (= 1 (.-readyState ws))) nil         ; open, unconfirmed: post
        (and ws (= 0 (.-readyState ws))) nil         ; CONNECTING: post this time
        :else
        (do
          (aset m w nil)
          (when-not (aget (or (.-wsopening this)
                              (do (set! (.-wsopening this) #js {}) (.-wsopening this))) w)
            (aset (.-wsopening this) w true)
            (-> (.fetch (.get ^js (.-VALIDATOR env)
                              (.idFromName ^js (.-VALIDATOR env) (do-name w)))
                        (js/Request. (str "https://v/peer?w=" w)
                                     #js {:headers #js {"Upgrade" "websocket"}}))
                (.then (fn [^js r]
                         (aset (.-wsopening this) w false)
                         (when-let [sock (.-webSocket r)]
                           (.accept sock)
                           ;; The probe, and the answer that makes the socket
                           ;; usable. One round trip, once per connection.
                           (.addEventListener
                            sock "message"
                            (fn [_]
                              (aset (or (.-wsok this)
                                        (do (set! (.-wsok this) #js {}) (.-wsok this)))
                                    w true)))
                           (.addEventListener
                            sock "close"
                            (fn [_] (aset (or (.-wsok this) #js {}) w false)))
                           (aset m w sock)
                           (try (.send sock "{\"probe\":1}") (catch :default _ nil)))))
                (.catch (fn [_] (aset (.-wsopening this) w false) nil))))
          nil))))

  ;; Sign each outbound vote and new-view, then post the batch to every peer.
  (dispatch [this outbox]
    ;; Counted HERE, where the messages actually leave.
    ;;
    ;; `/head` has been reporting `sent-types {}` and nothing ever wrote
    ;; `outtypes` — the field was read in one place and set in none, so the
    ;; number said "this replica has sent nothing" while it was sending. I was
    ;; one step from chasing that as the reason the chain stopped. An
    ;; instrument that is never written reads exactly like a measurement.
    (let [t (or (.-outtypes this) #js {})]
      (doseq [{:keys [msg]} outbox]
        (let [k (name (:type msg))]
          (gobj/set t k (inc (or (gobj/get t k) 0)))))
      (set! (.-outtypes this) t))
    (if (empty? outbox)
      (js/Promise.resolve nil)
      (-> (js/Promise.all
           (clj->js
            (for [{:keys [msg to]} outbox]
              (let [payload (case (:type msg)
                              :vote (att/vote-payload chain-id (:view msg) (:height msg)
                                                      (:block-hash msg) (.-witness this))
                              :new-view (att/new-view-payload chain-id (:view msg)
                                                              (.-witness this)
                                                              (:high-qc msg))
                              nil)]
                (if-not payload
                  (js/Promise.resolve {:to to :msg msg})
                  (-> (js/crypto.subtle.sign #js {:name "Ed25519"}
                                             (.-privateKey (.-kp this))
                                             (.encode (js/TextEncoder.) payload))
                      (.then (fn [s] {:to to :msg (assoc msg :sig (b64 s))}))))))))
          (.then (fn [envs]
                   (set! (.-msgs-out this) (+ (or (.-msgs-out this) 0) (count envs)))
                   ;; Fold our OWN signed messages back in.
                   ;;
                   ;; The replica folds its vote when it produces it, and here
                   ;; is where that vote acquires a signature — so what it
                   ;; recorded was the unsigned copy, and the certificate it
                   ;; built named three witnesses and carried two signatures.
                   ;; It certified fine locally and could not be verified by
                   ;; anybody else: w3 refused it below-quorum and could never
                   ;; catch up on a chain that was otherwise committing.
                   ;;
                   ;; Feeding the signed copy back replaces the record with
                   ;; one a peer can check. It is the same vote — same block,
                   ;; same height, same view — so this is not a second vote.
                   (doseq [{m :msg} envs
                           :when (and (:sig m) (= :vote (:type m)))]
                     ;; Cache our own signature as verified BEFORE folding.
                     ;;
                     ;; Removing the self-trust from verify-fn meant the
                     ;; replica now asks the cache about its own vote, and
                     ;; nothing had put it there — so it dropped the signed
                     ;; copy too, as `did-not-verify`, and with three
                     ;; potential voters against a quorum of three a single
                     ;; dropped vote is fatal. The chain sat at height two
                     ;; with one or two of these on every replica.
                     ;;
                     ;; Recording it rather than re-verifying: this Worker
                     ;; produced the signature a moment ago with its own key,
                     ;; and a WebCrypto call to learn that would be asking a
                     ;; question we already answered.
                     (aset (.-verified this)
                           (str (.-witness this) "|"
                                (att/vote-payload chain-id (:view m) (:height m)
                                                  (:block-hash m) (.-witness this))
                                "|" (:sig m))
                           true)
                     (let [[s' _] (r/on-message (.-replica this) m (js/Date.now))]
                       (set! (.-replica this) s')))
                   (js/Promise.all
                      (clj->js
                       ;; Delivery is recorded per peer. A message that was
                       ;; sent and a message that arrived are different
                       ;; facts, and every stall so far has been one of them
                       ;; being mistaken for the other.
                       ;;
                       ;; ## `:to` decides who gets it
                       ;;
                       ;; This used to serialise the whole outbox once and
                       ;; POST that one body to every peer, so `:to` — which
                       ;; `inga.replica` has always set — did nothing here.
                       ;; The effect was invisible for votes and proposals,
                       ;; which are broadcast anyway, and fatal for sync: a
                       ;; replica far behind received mostly the ANSWERS TO
                       ;; OTHER REPLICAS, each starting at a height it could
                       ;; not reach, and refused them one after another.
                       ;; Measured: a validator at height 0 offered a segment
                       ;; beginning at 1276 — the range a caught-up peer had
                       ;; asked about — reporting `:does-not-attach`.
                       ;;
                       ;; It was also the whole chain in one request. Three
                       ;; peers asking at once put three 256-block segments
                       ;; in a single body sent to all of them.
                       (for [w witnesses
                             :when (not= w (.-witness this))
                             :let [mine (filterv #(let [t (:to %)]
                                                    (or (nil? t) (= :all t) (= w t)))
                                                 envs)]
                             :when (seq mine)
                             :let [body (js/JSON.stringify
                                         (clj->js {:msgs (mapv (comp wire/encode :msg) mine)}))]]
                         (if-let [sock (.peerSocket this w)]
                           ;; Over the socket: no request to route, no handler
                           ;; to start, no response to wait for. Counted the
                           ;; same way, under its own key, so the two
                           ;; transports can be told apart in `/head`.
                           (try
                             (.send sock body)
                             (let [k (str w ":ws")]
                               (aset (.-delivery this) k
                                     (inc (or (aget (.-delivery this) k) 0))))
                             (js/Promise.resolve nil)
                             (catch :default _
                               ;; A socket that throws is gone. Drop it and
                               ;; post — the next dispatch opens a new one.
                               (aset (.-wsout this) w nil)
                               (.postTo this w body)))
                           (.postTo this w body)))))))
          (.then (fn [_] nil)))))

  (postTo [this w body]
    (-> (.fetch (.get ^js (.-VALIDATOR env)
                      (.idFromName ^js (.-VALIDATOR env) (do-name w)))
                (js/Request. (str "https://v/msg?w=" w)
                             #js {:method "POST" :body body}))
                             (.then (fn [^js r]
                                      (let [k (str w (if (.-ok r) ":ok" ":err"))]
                                        (aset (.-delivery this) k
                                              (inc (or (aget (.-delivery this) k) 0))))
                                      nil))
        (.catch (fn [_]
                  (let [k (str w ":throw")]
                    (aset (.-delivery this) k
                          (inc (or (aget (.-delivery this) k) 0))))
                  nil))))

  ;; NOTICE that the deployment has moved on. Nothing else.
  ;;
  ;; The Worker isolate is replaced by a deploy; a Durable Object is not — it
  ;; runs the code it booted with until evicted, and one firing an alarm every
  ;; few tens of milliseconds is never idle enough to be evicted. Measured
  ;; 2026-08-13: after deploying a new version the four replicas went on
  ;; reporting the old `code-version` indefinitely, and a `renamed_classes`
  ;; migration did not dislodge them either.
  ;;
  ;; So the half that DOES update tells the half that does not, and `/head`
  ;; reports it. **That is all it does**, deliberately.
  ;;
  ;; Standing down was written and taken back out. Stopping the alarm does not
  ;; make these objects idle — peers address them constantly with votes — so
  ;; it would not have caused the eviction it was for, and four replicas
  ;; standing down together would have stopped the chain to fix a problem that
  ;; is only about which build is running. A remedy that has to be staggered
  ;; across a quorum is not something to leave behind untested in a system
  ;; where deploys do not take effect.
  ;;
  ;; What this buys is that the next session sees the staleness in `/head`
  ;; instead of inferring it, which is how this was found in the first place.
  (staleCode? [this ^js request]
    (let [deployed (.get (.-headers request) "x-deployed-code-version")]
      (when (and deployed (not= deployed code-version))
        (set! (.-standingDown this) deployed)
        true)))

  (note! [this e]
    (set! (.-last-error this) (str (or (.-message e) e)))
    nil)

  ;; ── telling subscribers a block committed ─────────────────────────────────
  ;;
  ;; The terminal polled every 1500 ms, so the average wait between a block
  ;; committing and the page knowing was 750 ms — measured against an
  ;; end-to-end of about 2600 ms, it was the single largest term, and the only
  ;; one that costs nothing structural to remove. Blocks land ~336 ms apart,
  ;; so the poll was slower than the chain by a factor of four.
  ;;
  ;; This pushes a NOTIFICATION, not the state. The page still fetches what it
  ;; wants after being woken, which keeps one definition of every endpoint
  ;; instead of a second copy of `/head`, `/book` and `/trades` inlined into a
  ;; socket frame that would then have to be kept in agreement with them
  ;; forever. What the socket removes is the WAITING, which is all it was
  ;; costing.
  ;;
  ;; Hibernatable (`acceptWebSocket` rather than `accept`): a Durable Object
  ;; holding an ordinary socket cannot be evicted, and this object is one
  ;; whose eviction is routine and load-bearing — `witnesses` explains what
  ;; four replicas with a quorum of three do under churn. A subscriber must
  ;; not be able to pin a validator in memory by opening a tab.
  ;; ## Woken by the TIP, not by the commit
  ;;
  ;; This fired on `committed-height` first, and an A/B against the poll it
  ;; replaced measured it arriving 625 ms LATE at the median — the poll it was
  ;; supposed to beat was winning every single height.
  ;;
  ;; Chained HotStuff commits three rounds behind, so `committed` runs about
  ;; two blocks behind `height`, and `height` is what `/head` reports and what
  ;; the terminal puts on screen. Waking on the commit meant waking the page
  ;; two blocks after the number it displays had already changed.
  ;;
  ;; The tip is the right trigger because the page re-fetches everything when
  ;; woken: it gets the newest label AND the newest committed data in the same
  ;; pass. Waking more often than the data strictly changes costs one fetch;
  ;; waking later than the display changes costs the whole point.
  ;;
  ;; Both numbers ride along, so a subscriber that cares about finality rather
  ;; than about the tip does not have to ask a second time to tell them apart.
  (notify! [this]
    (let [h (r/height (.-replica this))]
      (when (not= h (.-notifiedAt this))
        (set! (.-notifiedAt this) h)
        (let [msg (js/JSON.stringify
                   #js {"height" h
                        "committed" (r/committed-height (.-replica this))
                        "state-root" (r/state-root (.-replica this))
                        "witness" (.-witness this)})]
          (doseq [ws (array-seq (.getWebSockets ^js do-state))]
            ;; One dead socket must not stop the others being told. Cloudflare
            ;; drops closed sockets itself, so there is nothing to clean up
            ;; here — only something not to throw out of.
            (try (.send ^js ws msg) (catch :default _ nil)))))))

  (tickNow [this]
    (when-not (.-bootAt this) (set! (.-bootAt this) (js/Date.now)))
    (-> (if (.-witness this)
          (js/Promise.resolve (.-witness this))
          (.get ^js (.-storage do-state) "witness"))
        (.then (fn [name]
                 (if name
                   (.boot this name)
                   ;; No name and no request to learn one from. Doing nothing
                   ;; is right: guessing would make this object impersonate a
                   ;; validator, which is the attack the rest of the system
                   ;; refuses.
                   (js/Promise.reject (js/Error. "alarm before any request")))))
        ;; Catch up BEFORE voting, and do not vote until caught up.
        ;;
        ;; This is a safety rule, not an optimisation. A replica that votes
        ;; from a partially folded log is voting at heights it has already
        ;; voted at, with a different machine state — which is equivocation,
        ;; the one thing this system slashes for, committed by accident
        ;; against itself. `r/replay` restores `:voted` for exactly that
        ;; reason; skipping ahead of it would throw the restoration away.
        (.then (fn [_] (.catchUp this)))
        (.then (fn [done?]
                 (when done?
                   (-> (.learnKeys this)
                       (.then (fn [_] (.round this)))
                       (.then (fn [_] (.persist! this)))
                       (.then (fn [_] (.checkpoint! this)))))))
        ;; The alarm must reschedule even when the tick threw, or one failure
        ;; stops the replica forever and it looks like a network problem.
        (.catch (fn [e] (.note! this e) nil))
        ;; RETURNED, not fired and forgotten. A Durable Object may be put to
        ;; sleep as soon as the handler resolves, and a setAlarm still in
        ;; flight is a tick that never happens — the loop stops with nothing
        ;; to read, which is what it did.
        (.then (fn [_]
                 ;; Stop the clock in an object nobody is talking to.
                 ;;
                 ;; Abandoned generations cannot be told anything — nothing
                 ;; routes to their ids any more — so they have to notice for
                 ;; themselves. A live replica receives `/msg` from its peers
                 ;; several times a second; an abandoned one receives nothing,
                 ;; ever. Not rescheduling is the only way one of them ends,
                 ;; because a self-rescheduling alarm is otherwise immortal.
                 ;;
                 ;; Generous window: a chain that is merely stalled still has
                 ;; peers posting votes and new-views every tick, so silence
                 ;; for two minutes means nobody is addressing this object at
                 ;; all. A canonical replica that somehow does go quiet is
                 ;; woken by the next request, which is how it started.
                 (let [q (or (.-lastInbound this) 0)
                       b (or (.-bootAt this) (js/Date.now))]
                   ;; `(zero? q)` used to be an EXEMPTION here — "never
                   ;; received anything, so keep going" — which is exactly
                   ;; backwards: an object nobody has ever addressed is
                   ;; precisely the abandoned one. `equivocators [w3 w4]` came
                   ;; back on a deployment with no Byzantine node while this
                   ;; was live, which is what a duplicate identity looks like.
                   ;;
                   ;; Measured from boot instead, so a genuinely new object
                   ;; gets its grace period and an old one that has been
                   ;; talking to nobody for two minutes stops.
                   (when (< (- (js/Date.now) (max q b)) 120000)
                     (.setAlarm ^js (.-storage do-state) (+ (js/Date.now) tick-ms))))))
        ;; An alarm handler that rejects is retried with backoff and then
        ;; dropped, and a dropped alarm is a chain that stops for good. It
        ;; must not reject, ever.
        (.catch (fn [e] (.note! this e) nil))))

  ;; Drives one round from outside. The alarm is the normal clock; this exists
  ;; because a clock you cannot step by hand is a clock you cannot debug, and
  ;; a stalled replica gives you nothing else to pull on.
  (step [this]
    (-> (.learnKeys this)
        (.then (fn [_] (.round this)))
        (.catch (fn [e] (.note! this e) nil))))

  (fetch [this ^js request]
    ;; Somebody is addressing this object. `tickNow` uses it to decide whether
    ;; to keep its clock running: an abandoned generation is addressed by
    ;; nobody, ever, and that is the only thing that distinguishes it.
    (set! (.-lastInbound this) (js/Date.now))
    (when-not (.-bootAt this) (set! (.-bootAt this) (js/Date.now)))
    (-> (.handle this request)
        (.catch (fn [e]
                  ;; An unhandled throw inside a Durable Object surfaces as an
                  ;; opaque 1101 with nothing to read. Reporting it is the
                  ;; difference between a bug and a mystery.
                  (json {:ok false :error (str (or (.-message e) e))
                         :witness (.-witness this)} 500)))))

  (round [this]
    ;; One round: bootstrap if there is nothing yet, and ALWAYS tick.
    ;;
    ;; It used to be one or the other, and at height zero that meant a
    ;; non-leader never reached on-tick — so its clock never started, it never
    ;; timed out, and it never sent a new-view. Three of four sat at height
    ;; zero, view zero, having sent nothing at all while receiving hundreds of
    ;; messages, and a manual step produced nothing either, which is what
    ;; finally said it was the branch and not the alarm.
    ;;
    ;; And the leader proposed the bootstrap block exactly once. Over HTTP to
    ;; peers that may not have been created yet, once is a message that can
    ;; simply be lost — and then the chain never starts, because the leader is
    ;; at height one needing a certificate it will never be given.
    ;; Re-proposing is safe: a block is a pure function of its parent now, so
    ;; it is the same block, byte for byte, every time.
    (let [now (js/Date.now)]
      ;; Where a block's 185 ms actually goes.
      ;;
      ;; `tick-ms` is 25 and blocks land every 185 ms, and the table under
      ;; `tick-ms` attributes the remainder to "the transport". Measured from
      ;; inside the objects, the transport is 2-4 ms between replicas in the
      ;; same colo (`/transport`), so the attribution cannot be right. These
      ;; three separate the candidates and cost two subtractions a tick:
      ;;
      ;;   gap   — wall time between consecutive ticks. If the alarm does not
      ;;           actually fire every 25 ms, nothing else matters.
      ;;   work  — time inside this function, dispatch included.
      ;;   block — wall time between height increments, from the inside.
      (let [prev (.-lastTickAt this)]
        (when prev
          (set! (.-tickGaps this)
                (let [a (or (.-tickGaps this) #js [])]
                  (.slice (.concat a (- now prev)) -64))))
        (set! (.-lastTickAt this) now))
      (let [h (r/height (.-replica this))]
        (when (not= h (.-lastHeight this))
          (when-let [t (.-lastHeightAt this)]
            (set! (.-blockGaps this)
                  (let [a (or (.-blockGaps this) #js [])]
                    (.slice (.concat a (- now t)) -64))))
          (set! (.-lastHeight this) h)
          (set! (.-lastHeightAt this) now)))
      (when (zero? (r/height (.-replica this)))
        (let [[s' out] (r/start (.-replica this) now)]
          (set! (.-replica this) s')
          (.queue! this out)))
      (let [[s' out] (r/on-tick (.-replica this) now)]
        (set! (.-replica this) s')
        (.queue! this out))
      ;; Both paths advance the chain, so both have to tell anybody listening.
      ;; `notify!` compares the committed height itself, so calling it when
      ;; nothing moved is a comparison and nothing else.
      (when (some #(= :proposal (:type (:msg %))) (or (.-outq this) []))
        (set! (.-proposeOnTick this) (inc (or (.-proposeOnTick this) 0))))
      (.notify! this)
      ;; Rate-limited inside. See `maybeAdopt!` for why this is on the tick
      ;; rather than behind the admin route it shares its evidence with.
      (.maybeAdopt! this)
      ;; Why this replica did NOT propose.
      ;;
      ;; Every counter here says what happened. A stall is the absence of
      ;; something happening, and the absence has a reason that nothing was
      ;; recording — so each stall so far has been diagnosed by adding one
      ;; more counter and waiting. This records the reason directly: the
      ;; three conditions `propose` checks, and which of them said no.
      (let [st (.-replica this)
            tip (r/tip st)
            th (:inga.block/height tip)
            next-h (inc th)
            ;; The replica keys `:qcs` and `:votes` by CID -- `:hash-fn` is
            ;; `block-cid`. This asked with `block-hash`, the identity that
            ;; stopped deciding what a block is, so it found nothing and said
            ;; so: `tip-certified false` and `votes-for-tip 0` on all four
            ;; replicas of a chain that was committing normally. Two
            ;; investigations took that reading as the symptom and went
            ;; looking for a missing vote that was recorded all along.
            certified? (some? (get (:qcs st) (block-cid tip)))
            leader (nth witnesses (mod next-h (count witnesses)))
            mine? (= leader (.-witness this))]
        (set! (.-why this)
              #js {"tip-height" th
                   "next-height" next-h
                   "tip-certified" certified?
                   "leader-of-next" leader
                   "my-turn" mine?
                   "would-propose" (and certified? mine?)
                   "blocked-by" (cond (not certified?) "no certificate for the tip"
                                      (not mine?) (str "not my turn, " leader " leads")
                                      :else "nothing")
                   "view" (:view (:pm st))
                   "deadline-in-ms" (- (:deadline (:pm st) 0) now)
                   "votes-for-tip" (count (get (:votes st) (block-cid tip) {}))}))

      ;; Re-broadcast the tip while it has no certificate.
      ;;
      ;; Retransmission is a transport concern and this is the transport. The
      ;; leader proposes once; if that message is lost, the receivers never
      ;; vote, the height never certifies, and the leader cannot propose again
      ;; because proposing needs the certificate it is waiting for. The chain
      ;; sits at that height forever with every replica holding the block and
      ;; nobody able to say so again.
      ;;
      ;; engi re-sends a vote when it sees the same block twice, which is the
      ;; other half and useless on its own: nothing was sending the block a
      ;; second time.
      (let [st (.-replica this)
            tip (r/tip st)
            h (:inga.block/height tip)
            n (inc (or (.-rounds this) 0))]
        (set! (.-rounds this) n)
        ;; Only the PROPOSER, and only every eighth round.
        ;;
        ;; Re-broadcasting from everybody on every tick was twenty thousand
        ;; proposals and eight thousand sync-requests per replica in a few
        ;; minutes — a storm that drowned the votes it was supposed to
        ;; rescue. A retransmission that costs more than the message it
        ;; replaces is not a retransmission.
        (when (and (pos? h)
                   (= (:inga.block/proposer tip) (.-witness this))
                   (zero? (mod n 8))
                   ;; CID, like everywhere the replica keys `:qcs`. Asked with
                   ;; `block-hash` this was always nil, so the guard "only
                   ;; while the tip has no certificate" never held and the
                   ;; proposer re-broadcast its tip every eighth round for the
                   ;; life of the chain — the storm this condition exists to
                   ;; prevent, at one eighth the rate.
                   (nil? (get (:qcs st) (block-cid tip))))
          (.queue! this [{:to :all :msg {:type :proposal :block tip}}])))
      ;; The tick does not wait out a slow peer.
      ;;
      ;; `dispatch` returns `Promise.all` over one `fetch` per peer, and this
      ;; awaited it before rearming — so the local clock ran at the speed of
      ;; the slowest replica. Measured from inside the objects:
      ;;
      ;;   tick gap   min 25   p50 29    max 1960
      ;;   tick work  min  0   p50  0    max 1922
      ;;   block      min 54   p50 247   max 1960
      ;;
      ;; The work is free at the median and occasionally two seconds, and the
      ;; block interval follows it exactly. Meanwhile `/transport` puts the
      ;; round trip between replicas at 2-4 ms in the same colo. **The
      ;; distance to Hyperliquid was never the transport — the floor here is
      ;; already 54 ms. It is this wait.**
      ;;
      ;; Bounded rather than removed: the sends keep running (a promise is not
      ;; cancelled by nobody holding it, and these objects rearm every 25 ms
      ;; so the isolate stays alive), and `waitUntil` says so explicitly where
      ;; the runtime offers it. What changes is that the clock stops being
      ;; hostage to them.
      (let [sends (js/Promise.resolve (.flush! this))
            t0 (js/Date.now)]
        (.then sends (fn [_]
                       (set! (.-deliverMs this)
                             (let [a (or (.-deliverMs this) #js [])]
                               (.slice (.concat a (- (js/Date.now) t0)) -64)))))
        (when (fn? (.-waitUntil do-state)) (.waitUntil do-state sends))
        (-> (js/Promise.race
             #js [sends (js/Promise. (fn [res] (js/setTimeout res deliver-cap-ms)))])
            (.then (fn [r]
                     (set! (.-tickWork this)
                           (let [a (or (.-tickWork this) #js [])]
                             (.slice (.concat a (- (js/Date.now) now)) -64)))
                     r))))))

  (rearm [this]
    ;; If no alarm is pending, set one. A loop that can be lost needs
    ;; something that notices it is gone, and every request is a free chance
    ;; to look — cheaper than a chain that stops silently and waits to be
    ;; wound by hand.
    ;; Sets an alarm only when there is none or the pending one is already
    ;; overdue.
    ;;
    ;; It used to overwrite unconditionally, which was a fix for a dropped
    ;; alarm still reading as pending — and it starved the clock. Every
    ;; inbound message runs this, so under a steady stream of peer traffic
    ;; each arrival pushed the alarm another 400ms into the future and it
    ;; never fired at all. Three of four validators sat at height zero having
    ;; sent nothing, ever, while receiving a hundred and sixty messages; the
    ;; fourth was the leader, got less traffic, found gaps, and ticked.
    ;;
    ;; A watchdog that resets the timer on every request is a timer that never
    ;; expires under load.
    (-> (.getAlarm ^js (.-storage do-state))
        (.then (fn [a]
                 (when (or (nil? a) (< a (js/Date.now)))
                   (.setAlarm ^js (.-storage do-state) (+ (js/Date.now) tick-ms)))))
        (.catch (fn [_] nil))))

  (alarmPending [this]
    ;; The clock itself, not its effects. Three stalls were diagnosed as
    ;; "the alarm is not firing" from the absence of its effects, and one of
    ;; those times the alarm was firing 153 times a minute.
    (-> (.getAlarm ^js (.-storage do-state))
        (.then (fn [a] (if a (- a (js/Date.now)) nil)))
        (.catch (fn [_] :unknown))))

  ;; Is this request allowed to destroy a replica?
  ;;
  ;; `/wipe` and `/reset` delete everything an object holds. They were open to
  ;; the internet: a `curl -X POST .../wipe?w=w1` from anywhere emptied a
  ;; validator, and emptying the one that leads halts the chain until every
  ;; replica is reset (see S14). They were written as operator tools and
  ;; deployed as public ones.
  ;;
  ;; A shared secret in `ADMIN_TOKEN`, compared in constant time. Absent from
  ;; the environment, the routes are refused rather than opened — a missing
  ;; secret is the state a fresh deployment is in, and defaulting that to
  ;; "allow" is how this was public in the first place.
  (adminOk [this ^js request]
    (let [want (.-ADMIN_TOKEN env)
          got (or (.get (.-headers request) "x-admin-token") "")]
      (and (string? want) (pos? (.-length want))
           ;; Constant time in the length that matters: comparing with `=`
           ;; leaks how many leading characters were right, which is a few
           ;; thousand requests rather than a search.
           (= (.-length want) (.-length got))
           (zero? (reduce (fn [acc i]
                            (bit-or acc (bit-xor (.charCodeAt want i)
                                                 (.charCodeAt got i))))
                          0 (range (.-length want)))))))

  (handle [this ^js request]
    (let [url (js/URL. (.-url request))
          path (.-pathname url)
          w (or (.get (.-searchParams url) "w") "w1")
          ;; Asked on every request rather than once: the deploy that makes
          ;; this object stale can happen at any moment, and the only signal
          ;; that it did is a request carrying a different version.
          _ (.staleCode? this request)]
      (if (and (#{"/wipe" "/reset" "/adopt"} path) (not (.adminOk this request)))
        (js/Promise.resolve
         (json {:ok false :reason "forbidden"
                :note "administrative route — x-admin-token required"} 403))
      (if (= path "/wipe")
        ;; BEFORE `boot`, deliberately, and this is the whole point of it.
        ;;
        ;; `/reset` already wipes a replica back to genesis, and it is behind
        ;; `boot` — so when boot itself is what fails, the route that exists
        ;; to repair the object cannot be reached. That is what the CPU crash
        ;; loop looked like from outside: an object that answered nothing,
        ;; including "throw yourself away".
        ;;
        ;; A recovery route must not depend on the state it repairs. This one
        ;; touches storage and its own flags and nothing else.
        ;;
        ;; PAGED, not `deleteAll`. `deleteAll` is one storage operation over
        ;; however many keys there are, and Cloudflare resets an object whose
        ;; storage operation runs too long — so on the storage that actually
        ;; needed wiping it failed with `storage operation exceeded timeout`
        ;; after sixteen seconds, which is the same shape of bug as the boot
        ;; replay it was written to rescue. A recovery path that is itself
        ;; unbounded is not a recovery path.
        ;;
        ;; One call deletes what it can inside a small budget and reports what
        ;; is left; the caller repeats until `remaining` is zero. Progress is
        ;; durable because the deletes are, so a reset mid-wipe costs one page
        ;; rather than the whole thing.
        (.wipePage this)
        (-> (.boot this w)
            (.then (fn [_] (.catchUp this)))
            (.then
             (fn [done?]
               (when-not done?
                 ;; Honest 503 rather than an answer read off a half-folded
                 ;; log. A terminal that shows a book from a state which never
                 ;; existed is worse than one that says it is not ready — the
                 ;; first invites a trade.
                 (json {:ok false :catching-up true
                        :witness (.-witness this)
                        :at (r/height (.-replica this))
                        :code-version code-version
                        ;; Set when a request arrived carrying a different
                        ;; deployed version. A reader seeing this knows the
                        ;; object is waiting to be reclaimed, not healthy.
                        :standing-down-for (.-standingDown this)}
                       503))))
            (.then
             (fn [early]
               (or early
                   (-> (.rearm this)
                       (.then
                        (fn [_]
                          (case path
                            ;; A socket that says only "a block committed".
                            ;; See `notify!` for why it carries a notification
                            ;; rather than the state.
                            "/subscribe"
                            (if-not (= "websocket"
                                       (some-> (.get (.-headers request) "Upgrade")
                                               (.toLowerCase)))
                              (json {:ok false :reason "expected-websocket-upgrade"} 426)
                              (let [pair (js/WebSocketPair.)]
                                (.acceptWebSocket ^js do-state (aget pair "1"))
                                (js/Response. nil #js {:status 101
                                                       :webSocket (aget pair "0")})))

                            ;; The peer side of the standing socket.
                            ;;
                            ;; Hibernatable and TAGGED, so `webSocketMessage`
                            ;; can tell a replica from a browser. `/subscribe`
                            ;; is one-way and its clients say nothing, but
                            ;; "says nothing today" is not a thing to route
                            ;; consensus messages on.
                            "/peer"
                            (if-not (= "websocket"
                                       (some-> (.get (.-headers request) "Upgrade")
                                               (.toLowerCase)))
                              (json {:ok false :reason "expected-websocket-upgrade"} 426)
                              (let [pair (js/WebSocketPair.)]
                                (.acceptWebSocket ^js do-state (aget pair "1") #js ["peer"])
                                (js/Response. nil #js {:status 101
                                                       :webSocket (aget pair "0")})))

                            ;; The EVM read surface, on the deployed chain.
                            ;;
                            ;; The same three answers the standalone gives, and
                            ;; deliberately the same code underneath: a contract
                            ;; that reads a position here and there must get the
                            ;; same number, and two implementations of `eth_call`
                            ;; would be two chances to disagree about what the
                            ;; exchange holds.
                            "/rpc"
                            (-> (.json request)
                                (.then
                                 (fn [body]
                                   (let [j (js->clj body :keywordize-keys true)
                                         ex (:machine-state (.-replica this))
                                         id (:id j)
                                         to (some-> (:to (first (:params j)))
                                                    clojure.string/lower-case)]
                                     (json
                                      (case (:method j)
                                        "eth_chainId" {:jsonrpc "2.0" :id id :result "0x539"}
                                        "net_version" {:jsonrpc "2.0" :id id :result "1337"}
                                        "eth_blockNumber"
                                        {:jsonrpc "2.0" :id id
                                         :result (str "0x" (.toString (r/height (.-replica this)) 16))}
                                        "eth_getCode"
                                        {:jsonrpc "2.0" :id id
                                         :result (str "0x" (get-in ex [:evm to :code] ""))}
                                        "eth_call"
                                        (let [data (:data (first (:params j)))
                                              out (or (evm/call ex to data)
                                                      (when-let [code (get-in ex [:evm to :code])]
                                                        (let [world (into {} (for [[a c] (:evm ex)]
                                                                               [a {:code (kc/hex->bytes (:code c))}]))
                                                              rr (evmi/run ex world
                                                                           {:address to
                                                                            :caller "0x0000000000000000000000000000000000000000"
                                                                            :depth 0}
                                                                           (kc/hex->bytes code) data)]
                                                          (when (= :return (:status rr))
                                                            (str "0x" (:data rr))))))]
                                          (if out
                                            {:jsonrpc "2.0" :id id :result out}
                                            {:jsonrpc "2.0" :id id
                                             :error {:code 3 :message "execution reverted"}}))
                                        {:jsonrpc "2.0" :id id
                                         :error {:code -32601
                                                 :message (str "method not found: " (:method j))}})
                                      200)))))

                            "/adopt"
                            (.adoptFromPeers this)

                            "/head"
               (let [s (.-replica this)]
                 (json {:witness (.-witness this)
                        :pubkey (.-pub this)
                        :code-version code-version
                        ;; So a restart can be READ rather than inferred: the
                        ;; checkpoint this replica last wrote, and whether it
                        ;; booted from one.
                        :checkpoint (.-lastCkpt this)
                        :booted-from-checkpoint (true? (.-bootedFromCkpt this))
                        :chain-id chain-id
                        :height (r/height s)
                        :resting (bk/resting-count
                                  (get-in (:machine-state s) [:books market-id]))
                        :committed (r/committed-height s)
                        :view (:view (:pm s))
                        :state-root (r/state-root s)
                        :peers-known (js/Object.keys (.-keys this))
                        :equivocators (vec (r/equivocators s))
                        ;; Diagnostics, because a Durable Object that is stuck
                        ;; gives you nothing else: no logs you can grep from
                        ;; here, and an opaque error code if it throws.
                        :votes-seen (reduce + 0 (map count (vals (:votes s))))
                        :vote-buckets (count (:votes s))
                        :certificates (count (:qcs s))
                        :verified-sigs (count (js/Object.keys (or (.-verified this) #js {})))
                        :msgs-in (or (.-msgs-in this) 0)
                        :msgs-out (or (.-msgs-out this) 0)
                        :queued (count (or (.-outq this) []))
                        ;; Two replicas that disagree about the genesis hash
                        ;; disagree about everything, silently: a proposal
                        ;; that does not extend the tip is refused with no
                        ;; message and no error, which is correct and gives
                        ;; you nothing to read.
                        ;; The CID, which is what consensus uses. These were
                        ;; computed here with `block-hash` while the replica
                        ;; identified blocks by `block-cid` — two definitions
                        ;; of what a block IS, in one file, disagreeing. The
                        ;; symptom was a `/head` reporting hex while every
                        ;; parent link in the chain was a CID.
                        :genesis-hash (block-cid (first (:chain (.-replica this))))
                        :tip-hash (block-cid (r/tip (.-replica this)))
                        :seen-types (js->clj (or (.-types this) #js {}))
                        ;; The mempool, and what has come out of it.
                        ;;
                        ;; `/tx` answers `queued true` and nothing observable
                        ;; follows; neither number existed, so "queued" was
                        ;; the last thing anyone could see about a
                        ;; transaction. `pending` says whether it is still
                        ;; waiting to be proposed and `txs-in-chain` whether
                        ;; any transaction has ever reached a block — one
                        ;; question each, and they fail in different places.
                        :ws-in (or (.-ws-in this) 0)
                        :pending (count (:pending (.-replica this)))
                        :txs-in-chain (reduce + 0 (map #(count (:inga.block/proposals %))
                                                       (:chain (.-replica this))))
                        :sent-types (js->clj (or (.-outtypes this) #js {}))
                        :last-sync-request (or (.-lastsync this) nil)
                        :why-not-proposing (js->clj (or (.-why this) #js {}))
                        :last-proposal (:last-proposal (.-replica this))
                        :last-sync-outcome (:last-sync (.-replica this))
                        ;; `propose`'s OWN answer, not one built out here.
                        ;; `why-not-proposing` decides whose turn it is by
                        ;; height; `propose` decides by round. Once the view
                        ;; runs ahead they name different replicas, and the
                        ;; outside answer is the wrong one.
                        :propose-refusal (:propose-refusal (.-replica this))
                        :timing (let [q (fn [a] (when (and a (pos? (.-length a)))
                                                  (let [v (vec (sort (array-seq a)))]
                                                    {:min (first v)
                                                     :p50 (nth v (quot (count v) 2))
                                                     :max (peek v)
                                                     :n (count v)})))]
                                  {:tick-gap-ms (q (.-tickGaps this))
                                   :tick-work-ms (q (.-tickWork this))
                                   :block-ms (q (.-blockGaps this))
                                   :deliver-ms (q (.-deliverMs this))
                                   :ingest-ms (q (.-ingestMs this))
                                   :ingest-phases (js->clj (or (.-ingestPhases this) #js []))
                                   :colo (.-colo this)
                                   :propose-on-msg (or (.-proposeOnMsg this) 0)
                                   :propose-on-tick (or (.-proposeOnTick this) 0)})
                        ;; Why the tip has no vote on it.
                        ;;
                        ;; `votes-for-tip 0` on every replica at once says the
                        ;; vote is missing; it does not say whether it was
                        ;; never cast, cast and dropped, or refused by the
                        ;; watermark a resume leaves. Each has a different fix
                        ;; and reading the code decided between them twice,
                        ;; wrongly. These are the three states themselves.
                        :vote-debug {:voted-below (:voted-below (.-replica this) -1)
                                     :voted-at-tip? (contains? (:voted (.-replica this))
                                                               (:inga.block/height
                                                                (r/tip (.-replica this))))
                                     :last-tip-vote (:last-tip-vote (.-replica this))
                                     :vote-keys (count (:votes (.-replica this)))
                                     :dropped-votes (:dropped-votes (.-replica this))
                                     :last-dropped-vote (:last-dropped-vote (.-replica this))}
                        :tip-certificate (r/tip-certificate (.-replica this))
                        :dropped-votes (:dropped-votes (.-replica this))
                        :last-dropped-vote (:last-dropped-vote (.-replica this))
                        :delivery (js->clj (or (.-delivery this) #js {}))

                        :last-error (or (.-last-error this) nil)
                        :consensus (str (c/quorum-size (count witnesses))
                                        " of " (count witnesses)
                                        " — chained HotStuff, inga.replica")
                        :key-distribution (key-distribution)
                        ;; The last time this replica took a state from a
                        ;; quorum, or nil. A silent repair is the same problem
                        ;; as a silent divergence.
                        :adopted (js->clj (or (.-adopted this) nil))
                        :adopt-check (js->clj (or (.-adoptCheck this) nil))
                        :next-adopt-check-in-ms (max 0 (- (or (.-nextAdoptCheck this) 0)
                                                          (js/Date.now)))
                        :transport "HTTP between Durable Objects, not WebSockets"
                        :tx-auth "signatures checked by torihiki.auth on every replica; public keys are raw Ed25519, base64"
                        :refused (frequencies
                                  (:refused (:machine-state (.-replica this))))}
                       200))

               "/msg"
               (-> (.json request)
                   (.then (fn [body]
                            (let [raw (js->clj (aget body "msgs"))
                                  msgs (keep (fn [m] (first (wire/decode m))) raw)]
                              (set! (.-msgs-in this) (+ (or (.-msgs-in this) 0) (count msgs)))
                              (set! (.-types this) (or (.-types this) #js {}))
                              (doseq [m msgs]
                                (let [k (name (:type m))]
                                  (aset (.-types this) k
                                        (inc (or (aget (.-types this) k) 0)))))
                              (-> (.ingest this (vec msgs))
                                  (.then (fn [_] (json {:ok true :n (count msgs)} 200)))))))
                   (.catch (fn [_] (json {:ok false :reason "bad-batch"} 400))))

               "/step"
               (-> (.step this)
                   (.then (fn [_] (json {:ok true :height (r/height (.-replica this))
                                         :committed (r/committed-height (.-replica this))} 200))))

               "/tx"
               ;; Shape only. Whether the account authorised it is decided by
               ;; torihiki.auth inside apply-block, on every replica, which is
               ;; where it has to be: a check done here would be this node
               ;; vouching for a transaction the others never examined.
               (-> (.text request)
                   (.then (fn [t]
                            (let [env (try (decode-tx t) (catch :default _ nil))]
                              (if-not (and env (:pubkey env) (:sig env)
                                           (integer? (:account env))
                                           (integer? (:nonce env)))
                                (json {:ok false :reason "malformed-envelope"} 400)
                                (-> (.verifyTx this env)
                                    (.then (fn [ok]
                                             (if ok
                                               (do (set! (.-replica this)
                                                         (r/submit (.-replica this) t))
                                                   (json {:ok true :queued true
                                                          :account (:account env)} 200))
                                               (json {:ok false
                                                      :reason "bad-signature"} 401))))))))))

               ;; Ask the bridge for collateral. The only way to get any.
               ;;
               ;; Refused once the account already holds some: a devnet faucet
               ;; that answers every time is a mint with extra steps, and the
               ;; point of giving collateral an issuer was to stop that.
               "/faucet"
               (if-not (= "POST" (.-method request))
                 (json {:ok false :reason "method"} 405)
                 (-> (.text request)
                     (.then (fn [t]
                              (let [m (try (js->clj (js/JSON.parse t) :keywordize-keys true)
                                           (catch :default _ nil))
                                    acct (:account m)
                                    ex (:machine-state (.-replica this))]
                                (cond
                                  (not (integer? acct))
                                  (js/Promise.resolve
                                   (json {:ok false :reason "bad-account"} 400))

                                  (pos? (get-in ex [:clearing :accounts acct :collateral] 0))
                                  (js/Promise.resolve
                                   (json {:ok false :reason "already-funded"
                                          :note "the bridge grants once per account"} 409))

                                  ;; Issued far ahead of what has committed:
                                  ;; grants are outrunning blocks, or one was
                                  ;; lost. Either way the next nonce would be
                                  ;; a guess, and a wrong guess is permanent.
                                  (> (- (or (.-lastFaucetNonce this) 0)
                                        (tauth/expected-nonce ex @faucet-account))
                                     faucet-nonce-lead)
                                  (js/Promise.resolve
                                   (json {:ok false :reason "bridge-behind"
                                          :note "grants are ahead of committed blocks — retry shortly"}
                                         503))

                                  :else
                                  (-> (.faucetGrant this acct)
                                      (.then (fn [r]
                                               (json (merge {:ok true :account acct
                                                             :amount faucet-grant} r)
                                                     200))))))))))

               ;; Block candles, from the machine — so they are the four
               ;; replicas' agreed fills rather than one replica's view.
               ;;
               ;; This existed on the single sequencer and not here, which is
               ;; the chain the terminal actually reads. The endpoint worked
               ;; and served nobody: the chart stayed inside the tape's
               ;; 200-fill window on the only chain a user ever sees.
               "/candles"
               (let [ex (:machine-state (.-replica this))
                     span (max 1 (or (js/parseInt (or (.get (.-searchParams url) "span") "10")) 1))
                     n (max 1 (min 1000 (or (js/parseInt (or (.get (.-searchParams url) "n") "200")) 200)))
                     ;; A vector is a checkpoint from before candles were
                     ;; per market; it is market 1's.
                     cs (:candles ex)
                     arr (vec (if (vector? cs) (when (= (market-param url) market-id) cs)
                                  (get cs (market-param url))))
                     floor (when (seq arr)
                             (- (cndl/bucket span (:h (peek arr))) (* span (dec n))))
                     window (if floor (filterv #(>= (:h %) floor) arr) [])]
                 (json {:market market-id
                        :span span
                        :unit "blocks"
                        :candles (cndl/rebucket span window)
                        :retained-from (when (seq arr) (:h (first arr)))
                        :retained-blocks (count arr)
                        :retention candle-retention
                        :truncated (boolean (and floor (> (:h (first arr)) floor)))
                        :truncated-because
                        (when (and floor (> (:h (first arr)) floor))
                          (if (>= (count arr) candle-retention) "retention" "no-older-blocks"))}
                       200))

               ;; An inclusion proof for one balance, against the root this
               ;; replica reports at `/head`.
               ;;
               ;; Also sequencer-only until now, and it mattered more here:
               ;; the point of a proof is not to trust the server, and there
               ;; is nothing to distrust about a chain of one writer that
               ;; already tells you it is a sequencer. Against four replicas
               ;; that vote, a proof a client checks itself is the difference
               ;; between believing a quorum and checking one.
               "/proof"
               (let [ex (:machine-state (.-replica this))
                     a (js/parseInt (or (.get (.-searchParams url) "account") "-1"))
                     p (cm/proof (st/canonical-leaves ex) (cm/account-leaf-id a))]
                 (if p
                   (json {:account a
                          :height (r/height (.-replica this))
                          :leaf-id (:id p)
                          :leaf-bytes (:bytes p)
                          :proof (:proof p)
                          :state-root (:root p)
                          :flat-root (st/flat-root ex)}
                         200)
                   ;; Absence is reported, not proved. A sorted-tree
                   ;; construction could prove non-membership; this is not
                   ;; one, and saying "no such account" as though it were a
                   ;; proof would be the more dangerous answer.
                   (json {:account a :proof nil :reason "no-such-account"
                          :note "absence is reported, not proved"}
                         404)))

               ;; A block, addressed. `cid` is its identity and `dag-cbor` is
               ;; the bytes that hash to it, base64 — so a client recomputes
               ;; the CID itself rather than believing this node's word for
               ;; it, and decodes the same bytes to read the block.
               ;;
               ;; By height rather than by CID because there is no cid→height
               ;; index yet; adding one costs a storage write per block and is
               ;; the next step, not this one. Stated rather than implied.
               "/block"
               (let [h (js/parseInt (or (.get (.-searchParams url) "height") "-1"))
                     s* (.-replica this)
                     b (first (filter #(= h (:inga.block/height %)) (:chain s*)))]
                 (if-not b
                   (json {:ok false :reason "not-held"
                          :note "this replica does not hold that block in memory"} 404)
                   (let [bytes (ipld/encode (block-node b))]
                     (json {:height h
                            :cid (ipld/cid bytes)
                            :codec "dag-cbor"
                            :dag-cbor (js/btoa (.apply js/String.fromCharCode nil
                                                       (js/Uint8Array.from bytes)))}
                           200))))

               "/account"
               (let [ex (:machine-state (.-replica this))
                     id (js/parseInt (or (.get (.-searchParams url) "id") "0"))]
                 (json (api/account-state ex id) 200))

               "/reset"
               ;; Wipe this replica back to genesis. It should rejoin by
               ;; asking its peers for what it missed — which is what
               ;; inga.sync is for and what nothing had exercised.
               (-> (.deleteAll ^js (.-storage do-state))
                   (.then (fn [_]
                            (set! (.-ready this) false)
                            (set! (.-persisted this) 0)
                            (.boot this w)))
                   (.then (fn [_] (json {:ok true :witness w
                                         :height (r/height (.-replica this))} 200))))

               "/transport"
               ;; What the transport actually costs, measured rather than
               ;; asserted.
               ;;
               ;; The tick-ms table ends with "the remaining distance to
               ;; Hyperliquid's ~0.07 s is not here — it is the transport,
               ;; which is HTTP between isolates and answers to co-location",
               ;; and that sentence was never measured. It names the fix
               ;; (co-location) for a cost nobody had timed and a spread
               ;; nobody had looked at. This times it: the round trip from
               ;; this object to each peer, and where each object actually
               ;; runs.
               ;;
               ;; `/clock` is the target because it touches storage the way
               ;; a real message does — timing a route that only returns a
               ;; constant would measure the runtime and call it the network.
               (let [peers (remove #(= (.-witness this) %) witnesses)
                     n 12]
                 (-> (js/Promise.all
                      (clj->js
                       (for [w peers]
                         (let [stub (.get ^js (.-VALIDATOR env)
                                          (.idFromName ^js (.-VALIDATOR env) (do-name w)))]
                           (-> (.reduce
                                (clj->js (vec (range n)))
                                (fn [acc _]
                                  (-> acc
                                      (.then (fn [^js xs]
                                               (let [t0 (js/Date.now)]
                                                 (-> (.fetch stub (str "https://v/clock?w=" w))
                                                     (.then #(.json %))
                                                     (.then (fn [_]
                                                              (.concat xs (clj->js [(- (js/Date.now) t0)]))))))))))
                                (js/Promise.resolve #js []))
                               (.then (fn [^js xs]
                                        (let [v (sort (array-seq xs))]
                                          {:peer w
                                           :min (first v)
                                           :p50 (nth v (quot (count v) 2))
                                           :max (last v)})))
                               (.catch (fn [e] {:peer w :error (str (or (.-message e) e))})))))))
                     (.then (fn [rs]
                              (json {:witness (.-witness this)
                                     ;; Where this object runs, from its own
                                     ;; outbound request — not from the edge
                                     ;; that served the caller, which is
                                     ;; wherever the caller happens to be.
                                     :colo (.-colo this)
                                     :samples n
                                     :round-trip-ms (vec (array-seq rs))}
                                    200)))))

               "/clock"
               ;; The clock itself, not its effects. Three stalls were called
               ;; "the alarm is not firing" from the absence of its effects,
               ;; and one of those times it was firing 153 times a minute.
               (-> (.alarmPending this)
                   (.then (fn [in-ms] (json {:witness (.-witness this)
                                             :alarm-in-ms in-ms} 200))))

               "/market"
               (json (api/market-info (:machine-state (.-replica this))
                                      (market-param url)) 200)

               "/trades"
               (let [ex (:machine-state (.-replica this))
                     m (market-param url)
                     n (js/parseInt (or (.get (.-searchParams url) "n") "20"))
                     ;; A print with no market is from before the tape carried
                     ;; one, and market 1 is what it could have been about.
                     mine (filter #(= m (:m % market-id)) (:tape ex []))]
                 (json {:market m
                        :trades (vec (reverse (take-last n mine)))} 200))

               ;; One account's fills, newest first.
               ;;
               ;; The first thing a trader asks for and the thing this could
               ;; not answer: the tape kept price, size and side and threw the
               ;; owners away, so there was nothing to filter by.
               ;;
               ;; `:side` is from THIS account's point of view — a maker on the
               ;; other side of a buy sold. Reporting the taker's side to both
               ;; parties would tell one of them the opposite of what happened.
               "/fills"
               (let [ex (:machine-state (.-replica this))
                     want (some-> (.get (.-searchParams url) "account") js/parseInt)
                     n (max 1 (min 500 (or (js/parseInt (or (.get (.-searchParams url) "n") "100")) 100)))
                     tape (:tape ex [])
                     mine (when want
                            (for [f tape
                                  :let [taker? (= want (:taker f))
                                        maker? (= want (:maker f))]
                                  :when (or taker? maker?)]
                              {:m (:m f market-id)
                               :h (:h f)
                               :level (:level f)
                               :qty (:qty f)
                               :role (if taker? "taker" "maker")
                               ;; `side` on a print is the TAKER's. A maker is
                               ;; on the other side of it by construction.
                               :side (if taker?
                                       (:side f)
                                       (if (zero? (:side f)) 1 0))}))]
                 (json {:account want
                        :fills (vec (reverse (take-last n (vec mine))))
                        ;; How far back this can see. A short history is a fact
                        ;; about the window, and an unstated one reads as
                        ;; "you have not traded".
                        :retained-from (:h (first tape))
                        :retention tape-retention}
                       200))

               "/book"
               (json (let [ex (:machine-state (.-replica this))]
                       (let [m (market-param url)]
                         {:market m
                          :bids (:bids (api/book-snapshot ex m 12))
                          :asks (:asks (api/book-snapshot ex m 12))
                          :resting (bk/resting-count (get-in ex [:books m]))}))
                     200)

               ;; An account's resting orders, WITH their ids.
               ;;
               ;; Nothing exposed an order id. `/book` aggregates by level and
               ;; `/account` carries collateral, positions and triggers, so a
               ;; client could place an order and then had no way to learn what
               ;; to name in order to cancel it — `:cancel` existed as a
               ;; transaction and was unreachable through the API.
               ;;
               ;; That was found by trying to prove the cancel authorization
               ;; fix against the live chain: the attempt could not get an id
               ;; to attack with, which is a strange kind of security and not
               ;; one to rely on.
               ;;
               ;; Walks the occupied levels rather than the whole ladder, so it
               ;; costs what the book HOLDS — the same property `state-root`
               ;; and the snapshot already have, and for the same reason.
               ;; This replica's newest checkpoint, verbatim. Public because
               ;; it is state a client could rebuild by replaying the chain —
               ;; serving it saves the replay, it does not reveal anything.
               ;; This replica's checkpoints. Public because it is state a
               ;; client could rebuild by replaying the chain — serving it
               ;; saves the replay, it does not reveal anything.
               ;;
               ;; `?h=` asks for one particular height. Without it the newest
               ;; is served, and `:heights` says what else is on offer — which
               ;; is what makes a comparison possible at all: replicas write
               ;; checkpoints at the same heights but not at the same moments,
               ;; so asking three peers for "the newest" gets three different
               ;; heights and nothing to compare.
               "/snapshot"
               (-> (.list ^js (.-storage do-state)
                          #js {:prefix "snap:" :reverse true :limit checkpoints-kept})
                   (.then (fn [^js entries]
                            (let [vals (js/Array.from (.values entries))
                                  ks (js/Array.from (.keys entries))
                                  hs (mapv #(js/parseInt (subs % (count "snap:")) 10)
                                           (array-seq ks))
                                  want (some-> (.get (.-searchParams url) "h")
                                               (js/parseInt 10))
                                  i (if want
                                      (first (keep-indexed #(when (= want %2) %1) hs))
                                      (when (pos? (alength vals)) 0))]
                              (if (nil? i)
                                (json {:ok false :reason "no-such-checkpoint"
                                       :heights hs} 404)
                                (json {:ok true
                                       :witness (.-witness this)
                                       :height (nth hs i)
                                       :heights hs
                                       ;; No root here on purpose: whoever
                                       ;; adopts computes it, because a peer
                                       ;; reporting its own root would be
                                       ;; trusted about the thing in dispute.
                                       :edn (aget vals i)}
                                      200))))))

               ;; Proof of reserves, the half a chain can produce by itself.
               ;;
               ;; `:total` is the root's merkle-sum: every account's collateral
               ;; added up, authenticated by the same root each account proves
               ;; its own share against. `:pending-withdrawals` is what has
               ;; left an account and not yet left the exchange — a claim is a
               ;; leaf and carries its amount, so the total does not drop when
               ;; somebody withdraws, only when the bridge settles.
               ;;
               ;; `:backing` says the part this cannot answer. With no
               ;; `:bridge-authority` the chain mints its own collateral, so
               ;; the number is exact and unbacked, and saying so here is the
               ;; difference between an attestation and a decoration.
               ;; Which markets exist. Without it a client has to guess an id
               ;; and read the fallback as an answer.
               "/markets"
               (if (= "POST" (.-method request))
                 ;; POST lists what this build has and the chain does not.
                 (-> (.listMissingMarkets this)
                     (.then (fn [r] (json (merge {:ok true} r) 200)))
                     (.catch (fn [e] (.note! this e)
                               (json {:ok false :reason (str (or (.-message e) e))} 500))))
               (let [ex (:machine-state (.-replica this))]
                 (json {:default market-id
                        :on-chain (vec (sort (keys (:markets ex))))
                        :in-build (mapv :id markets)
                        :markets (mapv (fn [m] (api/market-info ex m))
                                       (sort (keys (:markets ex))))}
                       200)))

               ;; An account's delegated keys, so a client can see what it has
               ;; authorised and when each one dies. Public: an agent public
               ;; key is public by construction, and hiding which keys may act
               ;; would hide it from the owner too.
               ;; Working TWAPs, so a trader can see what is still to be
               ;; executed on their behalf and cancel it.
               ;; A vault: what it holds, and who holds it.
               ;;
               ;; Public because a vault asking for outside money and not
               ;; publishing its book is asking to be trusted — the same
               ;; reason `/reserves` says what it cannot answer.
               ;; What an account has bonded, and what is on its way back.
               ;; An account's spot holdings, and what its resting orders have
               ;; already committed.
               "/balances"
               (let [c (:clearing (:machine-state (.-replica this)))
                     a (some-> (.get (.-searchParams url) "account") js/parseInt)]
                 (json {:account a
                        :balances (get-in c [:balances a] {})
                        :committed (get-in c [:committed a] {})}
                       200))

               "/stake"
               (let [ex (:machine-state (.-replica this))
                     c (:clearing ex)
                     a (some-> (.get (.-searchParams url) "account") js/parseInt)]
                 (json {:account a
                        :height (r/height (.-replica this))
                        :bonded (get-in c [:bonds a] {})
                        :total-bonded (cl/bonded c a)
                        :unbonding (vec (get-in c [:unbonding a] []))
                        :unbond-delay-blocks cl/unbond-delay-blocks
                        ;; What each publisher's word currently weighs. Empty
                        ;; while nobody has bonded, which is the honest answer
                        ;; rather than the genesis map dressed as stake.
                        :publisher-weight
                        (into {} (for [p (sort (:oracle-publishers ex))]
                                   [p (cl/stake-of c p)]))}
                       200))

               "/vault"
               (let [ex (:machine-state (.-replica this))
                     v (some-> (.get (.-searchParams url) "id") js/parseInt)
                     c (:clearing ex)]
                 (json {:vault v
                        :collateral (get-in c [:accounts v :collateral] 0)
                        :free (when v (cl/free-collateral c v (:marks ex) (:markets ex)))
                        :total-shares (get-in c [:vaults v :total-shares] 0)
                        :holders (vec (for [[a n] (sort (get-in c [:vaults v :shares] {}))]
                                        {:account a :shares n}))
                        :positions (get-in c [:accounts v :positions] {})}
                       200))

               "/twaps"
               (let [ex (:machine-state (.-replica this))
                     want (some-> (.get (.-searchParams url) "account") js/parseInt)]
                 (json {:account want
                        :height (r/height (.-replica this))
                        :twaps (vec (for [[id t] (sort (:twaps ex {}))
                                          :when (or (nil? want) (= want (:account t)))]
                                      (assoc (select-keys t [:market :side :remaining
                                                             :slices-left :every :next-at])
                                             :id id)))}
                       200))

               "/agents"
               (let [ex (:machine-state (.-replica this))
                     want (some-> (.get (.-searchParams url) "account") js/parseInt)]
                 (json {:account want
                        :height (r/height (.-replica this))
                        :owner-key (get-in ex [:account-keys want])
                        :agents (mapv (fn [[k v]]
                                        {:agent k :expires (:expires v)
                                         :live (or (nil? (:expires v))
                                                   (> (:expires v) (:height ex 0)))})
                                      (get-in ex [:agents want] {}))
                        :may-not (mapv name (sort tauth/agent-forbidden))}
                       200))

               "/reserves"
               (let [ex (:machine-state (.-replica this))
                     leaves (st/canonical-leaves ex)
                     ws (:withdrawals ex {})]
                 (json {:height (r/height (.-replica this))
                        :state-root (st/state-root ex)
                        :total (cm/reserves leaves)
                        :accounts (count (:accounts (:clearing ex)))
                        :pending-withdrawals {:count (count ws)
                                              :amount (reduce + 0 (map :amount (vals ws)))}
                        :insurance-fund (get-in ex [:clearing :insurance-fund] 0)
                        :bad-debt (reduce + 0 (keep :deficit (vals (:accounts (:clearing ex)))))
                        :bridge-authority (:bridge-authority ex)
                        ;; The asset side, as attested — and what it does not
                        ;; cover. nil means nobody has attested, which is not
                        ;; the same as a shortfall of zero: silence is not a
                        ;; claim.
                        :attested (:amount (:reserve-attestation ex))
                        :attested-at (:at (:reserve-attestation ex))
                        :shortfall (cm/shortfall leaves
                                                 (:amount (:reserve-attestation ex)))
                        :backing (if (:bridge-authority ex)
                                   (if (:reserve-attestation ex)
                                     "the bridge has attested what the escrow holds — compare :attested against :total, and :shortfall is the difference"
                                     "no attestation: the chain knows what it owes and has been told nothing about what backs it")
                                   "unbacked — this chain mints its own collateral, so :total is exact and backed by nothing")}
                       200))

               "/orders"
               (let [ex (:machine-state (.-replica this))
                     m (market-param url)
                     book (get-in ex [:books m])
                     want (some-> (.get (.-searchParams url) "account") js/parseInt)]
                 (json
                  {:market m
                   :account want
                   :orders
                   (vec (for [side [bk/bid bk/ask]
                              lvl (occupied-levels book side)
                              o (bk/level-orders book side lvl)
                              :when (or (nil? want) (= want (:owner o)))]
                          {:oid (:oid o) :side side :level lvl
                           :qty (:qty o) :owner (:owner o)}))}
                  200))

                            (json {:ok false :reason "not-found"} 404))))))))))))

  ))


;; ── the alarm, attached by its literal name ─────────────────────────────────
;;
;; `deftype` methods are renamed by the advanced compiler unless something
;; stops it. `fetch` survives because it is a name the externs already know;
;; `alarm` is not, so Cloudflare looked for a method that had been compiled
;; away and the loop never ran once. Every symptom pointed at the alarm firing
;; and doing nothing — the chain moved when /step was POSTed, so the clock
;; looked broken rather than absent — and three fixes went into the scheduling
;; before the handler itself turned out not to be there.
;;
;; `aset` with a string literal cannot be renamed.
;; `goog.object/set` rather than `aset`: aset on a prototype with an unused
;; result was eliminated outright, and the built bundle contained no "alarm"
;; anywhere — the fix compiled away as thoroughly as the bug did.
(gobj/set (.-prototype ValidatorV2) "alarm"
          (fn [] (this-as this (.tickNow ^js this))))

;; The hibernation API calls these by name on the object, so they are attached
;; the same way and for the same reason `alarm` is — a `deftype` method named
;; `webSocketMessage` is renamed by the advanced compiler and the runtime then
;; looks for something that is not there.
;;
;; Both are deliberately empty. `/subscribe` is one-way: the page is told that
;; a block committed and asks for what it wants over HTTP, so a subscriber has
;; nothing to say back. Defining them anyway is what keeps a client that sends
;; something — a stray ping, a reconnect probe — from being an unhandled call
;; on the object.
(gobj/set (.-prototype ValidatorV2) "webSocketMessage"
          (fn [^js ws msg]
            (this-as this
              ;; No tag check, and the first version's was worse than none.
              ;;
              ;; It asked `(.-ctx this)` for the socket's tags. The Durable
              ;; Object state is a `deftype` field here, not `ctx`, so the
              ;; lookup was `undefined`, the guard was false for every socket,
              ;; and every consensus message that arrived over a socket was
              ;; dropped in silence. The chain stopped at 15222 with the
              ;; sender reporting 149 delivered.
              ;;
              ;; It is not needed. `ingestBody` decodes and returns nil on
              ;; anything that is not a batch, and `/msg` already accepts the
              ;; same bytes over POST from anyone — a socket adds no reach
              ;; that was not there, and every message is signature-checked
              ;; before it counts for anything.
              (when (string? msg)
                (set! (.-ws-in ^js this) (inc (or (.-ws-in ^js this) 0)))
                ;; Answer the probe. This is the whole handshake: the sender
                ;; learns that something on this end is reading, which is the
                ;; fact an open socket does not carry.
                (if (= msg "{\"probe\":1}")
                  (try (.send ^js ws "{\"pong\":1}") (catch :default _ nil))
                  (.ingestBody ^js this msg)))
              nil)))
(gobj/set (.-prototype ValidatorV2) "webSocketClose" (fn [_ws _code _reason _clean] nil))
(gobj/set (.-prototype ValidatorV2) "webSocketError" (fn [_ws _err] nil))

(def handler
  #js {:fetch
       (fn [^js request ^js env _ctx]
         (if (= "OPTIONS" (.-method request))
           ;; A browser will not POST across an origin without asking first,
           ;; and an unanswered preflight is a page whose buttons fail with a
           ;; network error rather than a rejection. The sequencer answered
           ;; this from the day it had a terminal; the validator did not have
           ;; one until now.
           (js/Response. nil
                         #js {:headers #js {"access-control-allow-origin" "*"
                                            "access-control-allow-methods" "GET,POST,OPTIONS"
                                            "access-control-allow-headers" "content-type"}})
         (let [url (js/URL. (.-url request))
               w (or (.get (.-searchParams url) "w") "w1")
               ^js ns* (.-VALIDATOR env)]
           (if-not (some #{w} witnesses)
             (json {:ok false :reason "unknown-witness"} 404)
             ;; The deployed `code-version`, carried in on every request.
             ;;
             ;; The Worker isolate IS replaced by a deploy; a Durable Object is
             ;; not — it runs the code it booted with until it is evicted, and
             ;; one that fires an alarm every few tens of milliseconds is never
             ;; idle enough to be evicted. Measured 2026-08-13: after deploying
             ;; a new version, four replicas went on reporting the old
             ;; `code-version` indefinitely, and a `renamed_classes` migration
             ;; did not dislodge them either.
             ;;
             ;; So the half that DOES update tells the half that does not. The
             ;; object compares this against its own constant and stands down
             ;; when they differ — see `staleCode?`. This cannot rescue the
             ;; objects running today, because today's code does not read the
             ;; header; it means the next deploy after they restart is the last
             ;; one that needs a restart.
             (.fetch (.get ns* (.idFromName ns* (do-name w)))
                     (js/Request. request
                                  #js {:headers (doto (js/Headers. (.-headers request))
                                                  (.set "x-deployed-code-version"
                                                        code-version))}))))))})
