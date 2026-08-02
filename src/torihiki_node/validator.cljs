(ns torihiki-node.validator
  "Four validators, deployed.

  Everything before this ran four replicas in one Node process on one machine.
  The sockets were real and the consensus was real, but 'deployed' and 'runs
  on my laptop' are different claims and only one of them had been made good.

  This is one Worker holding four Durable Objects — w1 to w4 — each running
  `engi.replica` with `torihiki.state` as its machine, exchanging messages
  across isolate boundaries on Cloudflare's network.

  ## Why HTTP between them and not WebSockets

  `engi.replica` is transport-agnostic: `on-message` and `on-tick` return an
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
  fail closed, the same rule `engi.attest/lookup-verifier` states.

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
  unknown. Fail closed, the rule `engi.attest/lookup-verifier` states.

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

  The one that matters is `below-quorum`: `engi.sync` requires a quorum of
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

  `engi.sync` decides what a replica that has been away may believe from a
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
  `engi.replica/replay` on boot. Not re-verified: re-checking is re-litigating
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

  `engi.pacemaker` starts with a deadline of 0 and `on-tick` read that as no
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
  (:require [goog.object :as gobj]
            [engi.attest :as att]
            [engi.consensus :as c]
            [engi.replica :as r]
            [engi.wire :as wire]
            [kotoba.bytes.sha256 :as sha]
            [torihiki.address :as addr]
            [torihiki.auth :as tauth]
            [torihiki.api :as api]
            [torihiki.book :as bk]
            [torihiki.clearing :as cl]
            [torihiki.state :as st]))

(def ^:const chain-id "torihiki-engi-devnet-1")
(def ^:const pkcs8-ed25519-prefix
  "The 16 bytes PKCS8 puts in front of a raw Ed25519 seed. An Ed25519 private
  key IS its 32-byte seed; this header is what `importKey \"pkcs8\"` expects
  around it, and it is constant, so wrapping a seed is not a key format of my
  own invention."
  #js [0x30 0x2e 0x02 0x01 0x00 0x30 0x05 0x06 0x03 0x2b 0x65 0x70
       0x04 0x22 0x04 0x20])

(defn- derive-keypair
  "The keypair `witness` has on `chain`, the same one every time.

  See the call site for why this is derived rather than generated. Returns a
  promise of `#js {:privateKey k :pub b64}`."
  [chain witness]
  (let [seed-src (.encode (js/TextEncoder.) (str chain "|" witness "|ed25519"))]
    (-> (js/crypto.subtle.digest "SHA-256" seed-src)
        (.then (fn [d]
                 (let [seed (js/Uint8Array. d)
                       pk (js/Uint8Array. 48)]
                   (.set pk (js/Uint8Array.from pkcs8-ed25519-prefix) 0)
                   (.set pk seed 16)
                   (js/crypto.subtle.importKey "pkcs8" pk #js {:name "Ed25519"}
                                               true #js ["sign"]))))
        (.then (fn [sk]
                 ;; The public half is not derivable from the private key
                 ;; through WebCrypto, so it is carried by jwk round trip:
                 ;; export the private key as jwk, and its "x" member IS the
                 ;; public key, base64url. Converted to the standard base64
                 ;; every peer already exchanges.
                 (-> (js/crypto.subtle.exportKey "jwk" sk)
                     (.then (fn [^js j]
                              (let [x (aget j "x")
                                    std (-> x (.replace (js/RegExp. "-" "g") "+")
                                            (.replace (js/RegExp. "_" "g") "/"))
                                    pad (case (mod (.-length std) 4)
                                          2 "==" 3 "=" "")]
                                #js {:privateKey sk :pub (str std pad)})))))))))

(def ^:const code-version "72")

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
(def ^:const market-id 1)
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

  Keys are derived from the witness name, so adding three costs nothing but
  this line; there is no key to distribute for w5, w6 or w7."
  ;; REVERTED to four. Seven was deployed and the chain stopped at height two
  ;; with four votes against a quorum of five — three of the seven never voted
  ;; at all, where four witnesses had reached 139 in half the time. Whatever
  ;; the harness is modelling about churn, it is not modelling what these seven
  ;; Durable Objects do, and the measured penalty is not worth a chain that
  ;; does not run. The engi measurement stands; this deployment keeps four
  ;; until the three silent replicas are explained.
  ["w1" "w2" "w3" "w4"])
(def ^:const tick-ms 400)

;; ── the machine ─────────────────────────────────────────────────────────────

(def market
  (assoc (cl/market {:id market-id :max-leverage 40 :tick 10 :lot 1})
         :taker-fee-rate 350000
         :maker-fee-rate 100000))

(defn genesis []
  (-> (st/new-exchange {:market market
                        :book-opts {:n-levels 65536 :cap 16384 :ev-cap 8192}})
      (st/apply-tx {:tx :oracle :market market-id :price 1000})))

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

(defn- json [x status]
  (js/Response. (js/JSON.stringify (clj->js x))
                #js {:status status
                     :headers #js {"content-type" "application/json"
                                   "access-control-allow-origin" "*"}}))

(deftype Validator [^js do-state ^js env]
  Object
  ;; Boot: the witness name, the signing key, and the replica. Everything is
  ;; in memory — this is a devnet and a validator that is evicted rejoins by
  ;; catching up, which is what engi.sync is for.
  (boot [this name]
    ;; Re-boots when the name it holds is not the name it is being addressed
    ;; by. A Durable Object keeps running the code and the state it started
    ;; with until it is evicted, so a deploy that fixes a naming bug does not
    ;; fix the objects already holding the wrong name — three validators went
    ;; on believing they were w1 across two deploys, and only self-healing on
    ;; the mismatch cleared it.
    (if (and (.-ready this) (= (.-witness this) name))
      (js/Promise.resolve nil)
      (-> (.get ^js (.-storage do-state) "key")
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
                   (derive-keypair chain-id name)))
          (.then (fn [^js k]
                   (set! (.-kp this) k)
                   (set! (.-pub this) (.-pub k))
                   (set! (.-witness this) name)
                   (set! (.-keys this) #js {})
                   (set! (.-verified this) #js {})
                   (set! (.-delivery this) #js {})
                   (set! (.-why this) #js {})
                   (set! (.-replica this)
                         (r/replica {:witness name
                                     :witnesses witnesses
                                     :quorum (c/quorum-size (count witnesses))
                                     :hash-fn (fn [b] (block-hash (c/canonical-block b)))
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
                                         ex {:height (:engi.block/height block)
                                             :ts (:engi.block/ts block)
                                             :txs (mapv decode-tx
                                                        (:engi.block/proposals block))}
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
                                            (as-> ex'
                                                  (update ex' :tape
                                                          (fn [t]
                                                            (into []
                                                                  (take-last
                                                                   200
                                                                   (concat
                                                                    (or t [])
                                                                    (map (fn [f]
                                                                           {:level (:level f)
                                                                            :qty (:qty f)
                                                                            :side (:taker-side f)
                                                                            :h (:engi.block/height block)})
                                                                         (bk/fills (get-in ex' [:books market-id])))))))))
                                            ;; apply-block resets :rejected
                                            ;; every block, so a fold ends
                                            ;; holding only the last one's —
                                            ;; which reads as nothing ever
                                            ;; having been refused.
                                            (as-> ex' (update ex' :refused
                                                              (fnil into [])
                                                              (map :reason
                                                                   (:rejected ex'))))))
                                      :root-fn st/state-root}}))
                   (set! (.-persisted this) 0)
                   (set! (.-ready this) true)
                   ;; RETURNED. This was fired and forgotten, and a Durable
                   ;; Object may be put to sleep as soon as the handler
                   ;; resolves — so the very first alarm was lost and the loop
                   ;; never started. The chain then only moved when something
                   ;; POSTed /step, which looked like the alarm firing and
                   ;; doing nothing rather than never firing at all.
                   (-> (.list ^js (.-storage do-state) #js {:prefix "blk:"})
                       (.then (fn [entries]
                                (let [blocks (keep (fn [raw]
                                                     (let [[m _] (wire/decode
                                                                  (js->clj (js/JSON.parse raw)))]
                                                       (:block m)))
                                                   (js/Array.from (.values entries)))]
                                  (when (seq blocks)
                                    (set! (.-replica this)
                                          (r/replay (.-replica this) (vec blocks)))
                                    (set! (.-persisted this)
                                          (count (:chain (.-replica this)))))
                                  nil)))
                       (.then (fn [_] (.put ^js (.-storage do-state) "witness" name)))
                       (.then (fn [_]
                                (.setAlarm ^js (.-storage do-state)
                                           (+ (js/Date.now) tick-ms))))))))))

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
  ;; edge — the shape `engi.attest/pending-checks` exists for.
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
            raw (:engi.block/proposals (:block m))
            :let [env (try (decode-tx raw) (catch :default _ nil))]
            :when (and env (:pubkey env) (:sig env) (integer? (:account env)))
            ]
        (.verifyTx this env))))
      (catch :default e (.note! this e) (js/Promise.resolve nil))))

  (ingest2 [this msgs]
    (-> (.verifyTxs this msgs)
        (.then (fn [_] (.foldMsgs this msgs)))))

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
                   ;; Queued, not sent. See the namespace docstring: sending
                   ;; from here is what turned one message into nine.
                   (.queue! this out)
                   (.persist! this))))))

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
                      (str "blk:" (.padStart (str (:engi.block/height b)) 12 "0"))
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
            (for [{:keys [msg]} outbox]
              (let [payload (case (:type msg)
                              :vote (att/vote-payload chain-id (:view msg) (:height msg)
                                                      (:block-hash msg) (.-witness this))
                              :new-view (att/new-view-payload chain-id (:view msg)
                                                              (.-witness this)
                                                              (:high-qc msg))
                              nil)]
                (if-not payload
                  (js/Promise.resolve msg)
                  (-> (js/crypto.subtle.sign #js {:name "Ed25519"}
                                             (.-privateKey (.-kp this))
                                             (.encode (js/TextEncoder.) payload))
                      (.then (fn [s] (assoc msg :sig (b64 s))))))))))
          (.then (fn [signed]
                   (set! (.-msgs-out this) (+ (or (.-msgs-out this) 0) (count signed)))
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
                   (doseq [m signed
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
                   (let [body (js/JSON.stringify
                               (clj->js {:msgs (mapv wire/encode signed)}))]
                     (js/Promise.all
                      (clj->js
                       ;; Delivery is recorded per peer. A message that was
                       ;; sent and a message that arrived are different
                       ;; facts, and every stall so far has been one of them
                       ;; being mistaken for the other.
                       (for [w witnesses :when (not= w (.-witness this))]
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
                                       nil)))))))))
          (.then (fn [_] nil)))))

  (note! [this e]
    (set! (.-last-error this) (str (or (.-message e) e)))
    nil)

  (tickNow [this]
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
        (.then (fn [_] (.learnKeys this)))
        (.then (fn [_] (.round this)))
        (.then (fn [_] (.persist! this)))
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
                 (let [q (or (.-lastInbound this) 0)]
                   (when (or (zero? q) (< (- (js/Date.now) q) 120000))
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
      (when (zero? (r/height (.-replica this)))
        (let [[s' out] (r/start (.-replica this) now)]
          (set! (.-replica this) s')
          (.queue! this out)))
      (let [[s' out] (r/on-tick (.-replica this) now)]
        (set! (.-replica this) s')
        (.queue! this out))
      ;; Why this replica did NOT propose.
      ;;
      ;; Every counter here says what happened. A stall is the absence of
      ;; something happening, and the absence has a reason that nothing was
      ;; recording — so each stall so far has been diagnosed by adding one
      ;; more counter and waiting. This records the reason directly: the
      ;; three conditions `propose` checks, and which of them said no.
      (let [st (.-replica this)
            tip (r/tip st)
            th (:engi.block/height tip)
            next-h (inc th)
            certified? (some? (get (:qcs st) (block-hash (c/canonical-block tip))))
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
                   "votes-for-tip" (count (get (:votes st)
                                               (block-hash (c/canonical-block tip)) {}))}))

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
            h (:engi.block/height tip)
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
                   (= (:engi.block/proposer tip) (.-witness this))
                   (zero? (mod n 8))
                   (nil? (get (:qcs st) (block-hash (c/canonical-block tip)))))
          (.queue! this [{:to :all :msg {:type :proposal :block tip}}])))
      (.flush! this)))

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

  (handle [this ^js request]
    (let [url (js/URL. (.-url request))
          path (.-pathname url)
          w (or (.get (.-searchParams url) "w") "w1")]
      (-> (.boot this w)
          (.then (fn [_] (.rearm this)))
          (.then
           (fn [_]
             (case path
               "/head"
               (let [s (.-replica this)]
                 (json {:witness (.-witness this)
                        :pubkey (.-pub this)
                        :code-version code-version
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
                        :genesis-hash (block-hash (c/canonical-block
                                                   (first (:chain (.-replica this)))))
                        :tip-hash (block-hash (c/canonical-block
                                               (r/tip (.-replica this))))
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
                        :pending (count (:pending (.-replica this)))
                        :txs-in-chain (reduce + 0 (map #(count (:engi.block/proposals %))
                                                       (:chain (.-replica this))))
                        :sent-types (js->clj (or (.-outtypes this) #js {}))
                        :last-sync-request (or (.-lastsync this) nil)
                        :why-not-proposing (js->clj (or (.-why this) #js {}))
                        :last-proposal (:last-proposal (.-replica this))
                        :last-sync-outcome (:last-sync (.-replica this))
                        :tip-certificate (r/tip-certificate (.-replica this))
                        :dropped-votes (:dropped-votes (.-replica this))
                        :last-dropped-vote (:last-dropped-vote (.-replica this))
                        :delivery (js->clj (or (.-delivery this) #js {}))

                        :last-error (or (.-last-error this) nil)
                        :consensus (str (c/quorum-size (count witnesses))
                                        " of " (count witnesses)
                                        " — chained HotStuff, engi.replica")
                        :key-distribution "trust-on-first-use — a devnet answer, not a real one"
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

               "/account"
               (let [ex (:machine-state (.-replica this))
                     id (js/parseInt (or (.get (.-searchParams url) "id") "0"))]
                 (json (api/account-state ex id) 200))

               "/reset"
               ;; Wipe this replica back to genesis. It should rejoin by
               ;; asking its peers for what it missed — which is what
               ;; engi.sync is for and what nothing had exercised.
               (-> (.deleteAll ^js (.-storage do-state))
                   (.then (fn [_]
                            (set! (.-ready this) false)
                            (set! (.-persisted this) 0)
                            (.boot this w)))
                   (.then (fn [_] (json {:ok true :witness w
                                         :height (r/height (.-replica this))} 200))))

               "/clock"
               ;; The clock itself, not its effects. Three stalls were called
               ;; "the alarm is not firing" from the absence of its effects,
               ;; and one of those times it was firing 153 times a minute.
               (-> (.alarmPending this)
                   (.then (fn [in-ms] (json {:witness (.-witness this)
                                             :alarm-in-ms in-ms} 200))))

               "/market"
               (json (api/market-info (:machine-state (.-replica this)) market-id) 200)

               "/trades"
               (let [ex (:machine-state (.-replica this))
                     n (js/parseInt (or (.get (.-searchParams url) "n") "20"))]
                 (json {:market market-id
                        :trades (vec (reverse (take-last n (:tape ex []))))} 200))

               "/book"
               (json (let [ex (:machine-state (.-replica this))]
                       {:market market-id
                        :bids (:bids (api/book-snapshot ex market-id 12))
                        :asks (:asks (api/book-snapshot ex market-id 12))
                        :resting (bk/resting-count (get-in ex [:books market-id]))})
                     200)

               (json {:ok false :reason "not-found"} 404)))))))

  )


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
(gobj/set (.-prototype Validator) "alarm"
          (fn [] (this-as this (.tickNow ^js this))))

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
             (.fetch (.get ns* (.idFromName ns* (do-name w))) request)))))})
