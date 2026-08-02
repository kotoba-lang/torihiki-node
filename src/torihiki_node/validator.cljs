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

  ## Open: two genuine transactions were refused with bad-nonce

  A forged transaction is refused with `:bad-signature`, which is the check
  working. But the two genuine ones submitted alongside it — a deposit at
  nonce 1 and an order at nonce 2, sent to two different replicas — were both
  refused `:bad-nonce`, and the account ends with no collateral. Submitting to
  two replicas means the chain, not the client, decides the order, so nonce 2
  arriving first would explain one refusal and not both. Unresolved.

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
(def ^:const code-version "23")

(defn- do-name
  "The Durable Object id for a witness, versioned.

  A Durable Object keeps running the code it started with until it is evicted,
  and a DO with a self-rescheduling 400ms alarm NEVER goes idle — so the very
  mechanism that makes it a running chain makes it impossible to update. Three
  minutes of complete silence did not evict it; polling to check whether the
  new version was live was itself keeping the old version alive.

  Putting the version in the name means a deploy creates new objects. The
  outer fetch handler always runs the new code, so it addresses the new ones,
  and the old ones talk among themselves until they idle out. State is lost on
  upgrade, which is honest here because it was only ever in memory."
  [w]
  (str w "-v" code-version))
(def ^:const market-id 1)
(def witnesses ["w1" "w2" "w3" "w4"])
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
                   (if stored
                     (-> (js/crypto.subtle.importKey
                          "pkcs8" (b64-> (aget stored "priv"))
                          #js {:name "Ed25519"} true #js ["sign"])
                         (.then (fn [sk]
                                  #js {:privateKey sk :pub (aget stored "pub")})))
                     (-> (js/crypto.subtle.generateKey #js {:name "Ed25519"} true
                                                       #js ["sign" "verify"])
                         (.then (fn [^js kp]
                                  (js/Promise.all
                                   #js [(js/crypto.subtle.exportKey "pkcs8" (.-privateKey kp))
                                        (js/crypto.subtle.exportKey "raw" (.-publicKey kp))
                                        (js/Promise.resolve kp)])))
                         (.then (fn [[priv pub kp]]
                                  (-> (.put ^js (.-storage do-state) "key"
                                            #js {"priv" (b64 priv) "pub" (b64 pub)})
                                      (.then (fn [_]
                                               #js {:privateKey (.-privateKey kp)
                                                    :pub (b64 pub)})))))))))
          (.then (fn [^js k]
                   (set! (.-kp this) k)
                   (set! (.-pub this) (.-pub k))
                   (set! (.-witness this) name)
                   (set! (.-keys this) #js {})
                   (set! (.-verified this) #js {})
                   (set! (.-replica this)
                         (r/replica {:witness name
                                     :witnesses witnesses
                                     :quorum (c/quorum-size (count witnesses))
                                     :hash-fn (fn [b] (block-hash (c/canonical-block b)))
                                     :chain-id chain-id
                                     :verify-fn (fn [w payload sig]
                                                  (or (= w name)
                                                      (.verifyCached this w payload sig)))
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
      ;; Re-asked every tick rather than once. A cached key that is wrong is
      ;; indistinguishable from a peer that is silent, and the cost of asking
      ;; is one request against a stall nobody can see.
      (for [w witnesses :when (not= w (.-witness this))]
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
            :let [payload (tauth/signing-payload chain-id (:account env)
                                                 (:nonce env) (:tx env))
                  k (str (:pubkey env) "|" payload "|" (:sig env))]]
        (-> (js/crypto.subtle.importKey "raw" (b64-> (:pubkey env))
                                        #js {:name "Ed25519"} false #js ["verify"])
            (.then (fn [pk] (js/crypto.subtle.verify
                             #js {:name "Ed25519"} pk (b64-> (:sig env))
                             (.encode (js/TextEncoder.) payload))))
            (.then (fn [ok] (aset (.-txok this) k (true? ok)) nil))
            (.catch (fn [_] nil))))))
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
                   (let [body (js/JSON.stringify
                               (clj->js {:msgs (mapv wire/encode signed)}))]
                     (js/Promise.all
                      (clj->js
                       (for [w witnesses :when (not= w (.-witness this))]
                         (-> (.fetch (.get ^js (.-VALIDATOR env)
                                           (.idFromName ^js (.-VALIDATOR env) (do-name w)))
                                     (js/Request. (str "https://v/msg?w=" w)
                                                  #js {:method "POST" :body body}))
                             (.catch (fn [_] nil)))))))))
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
        (.then (fn [_]
                 (if (zero? (r/height (.-replica this)))
                   (let [[s' out] (r/start (.-replica this) (js/Date.now))]
                     (set! (.-replica this) s')
                     (.queue! this out)
                     (.flush! this))
                   (let [[s' out] (r/on-tick (.-replica this) (js/Date.now))]
                     (set! (.-replica this) s')
                     (.queue! this out)
                     (.flush! this)))))
        (.then (fn [_] (.persist! this)))
        ;; The alarm must reschedule even when the tick threw, or one failure
        ;; stops the replica forever and it looks like a network problem.
        (.catch (fn [e] (.note! this e) nil))
        ;; RETURNED, not fired and forgotten. A Durable Object may be put to
        ;; sleep as soon as the handler resolves, and a setAlarm still in
        ;; flight is a tick that never happens — the loop stops with nothing
        ;; to read, which is what it did.
        (.then (fn [_]
                 (.setAlarm ^js (.-storage do-state) (+ (js/Date.now) tick-ms))))
        ;; An alarm handler that rejects is retried with backoff and then
        ;; dropped, and a dropped alarm is a chain that stops for good. It
        ;; must not reject, ever.
        (.catch (fn [e] (.note! this e) nil))))

  ;; Drives one round from outside. The alarm is the normal clock; this exists
  ;; because a clock you cannot step by hand is a clock you cannot debug, and
  ;; a stalled replica gives you nothing else to pull on.
  (step [this]
    (-> (.learnKeys this)
        (.then (fn [_]
                 (if (zero? (r/height (.-replica this)))
                   (let [[s' out] (r/start (.-replica this) (js/Date.now))]
                     (set! (.-replica this) s')
                     (.queue! this out)
                     (.flush! this))
                   (let [[s' out] (r/on-tick (.-replica this) (js/Date.now))]
                     (set! (.-replica this) s')
                     (.queue! this out)
                     (.flush! this)))))
        (.catch (fn [e] (.note! this e) nil))))

  (fetch [this ^js request]
    (-> (.handle this request)
        (.catch (fn [e]
                  ;; An unhandled throw inside a Durable Object surfaces as an
                  ;; opaque 1101 with nothing to read. Reporting it is the
                  ;; difference between a bug and a mystery.
                  (json {:ok false :error (str (or (.-message e) e))
                         :witness (.-witness this)} 500)))))

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
                        :last-error (or (.-last-error this) nil)
                        :consensus (str (c/quorum-size (count witnesses))
                                        " of " (count witnesses)
                                        " — chained HotStuff, engi.replica")
                        :key-distribution "trust-on-first-use — a devnet answer, not a real one"
                        :transport "HTTP between Durable Objects, not WebSockets"
                        :tx-auth "signatures checked by torihiki.auth on every replica"
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
                              (if (and env (:pubkey env) (:sig env)
                                       (integer? (:account env))
                                       (integer? (:nonce env)))
                                (do (set! (.-replica this)
                                          (r/submit (.-replica this) t))
                                    (json {:ok true :queued true
                                           :account (:account env)} 200))
                                (json {:ok false :reason "malformed-envelope"}
                                      400))))))

               "/account"
               (let [ex (:machine-state (.-replica this))
                     id (js/parseInt (or (.get (.-searchParams url) "id") "0"))]
                 (json (api/account-state ex id) 200))

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
         (let [url (js/URL. (.-url request))
               w (or (.get (.-searchParams url) "w") "w1")
               ^js ns* (.-VALIDATOR env)]
           (if-not (some #{w} witnesses)
             (json {:ok false :reason "unknown-witness"} 404)
             (.fetch (.get ns* (.idFromName ns* (do-name w))) request))))})
