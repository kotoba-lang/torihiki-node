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

  ## What is actually wrong now: the replica state is in memory

  Three votes, three buckets — every replica voted for a DIFFERENT height-one
  block, so no two votes are for the same decision and quorum can never form.

  The reason is that a replica is rebuilt from `genesis` on every boot, and a
  Durable Object is evicted routinely. w2 leads height one; each time it comes
  back it is at height zero again and proposes a fresh block, whose timestamp
  makes it a different block from the one before. The network accumulates
  incompatible height-one proposals forever.

  In one process this cannot happen — nothing restarts. Deployed, it is fatal
  and it is not a bug in any of the fixes below: the chain, the pacemaker
  state and the machine have to be persisted and replayed on boot, the way
  `torihiki-node`'s sequencer already persists its transaction log. That is
  the next piece of work and it has not been done.

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
            [torihiki.api :as api]
            [torihiki.book :as bk]
            [torihiki.clearing :as cl]
            [torihiki.state :as st]))

(def ^:const chain-id "torihiki-engi-devnet-1")
(def ^:const code-version "15")

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
                                        (st/apply-block
                                         ex {:height (:engi.block/height block)
                                             :ts (:engi.block/ts block)
                                             :txs (mapv decode-tx
                                                        (:engi.block/proposals block))}
                                         {:chain-id chain-id
                                          :verify-fn (fn [_ _ _] true)
                                          :derive-account addr/derive}))
                                      :root-fn st/state-root}}))
                   (set! (.-ready this) true)
                   ;; RETURNED. This was fired and forgotten, and a Durable
                   ;; Object may be put to sleep as soon as the handler
                   ;; resolves — so the very first alarm was lost and the loop
                   ;; never started. The chain then only moved when something
                   ;; POSTed /step, which looked like the alarm firing and
                   ;; doing nothing rather than never firing at all.
                   (-> (.put ^js (.-storage do-state) "witness" name)
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

  (ingest2 [this msgs]
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
                   nil)))))

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
    ;; Always overwrites, rather than only setting when none is pending.
    ;; Cloudflare drops an alarm after repeated failures, and a dropped alarm
    ;; still reads as "one is pending" to the check that was here — so the
    ;; watchdog looked at a corpse and decided nothing needed doing. The queue
    ;; sat at one message for four minutes with no error recorded anywhere.
    (-> (.setAlarm ^js (.-storage do-state) (+ (js/Date.now) tick-ms))
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
                        :last-error (or (.-last-error this) nil)
                        :consensus (str (c/quorum-size (count witnesses))
                                        " of " (count witnesses)
                                        " — chained HotStuff, engi.replica")
                        :key-distribution "trust-on-first-use — a devnet answer, not a real one"
                        :transport "HTTP between Durable Objects, not WebSockets"}
                       200))

               "/msg"
               (-> (.json request)
                   (.then (fn [body]
                            (let [raw (js->clj (aget body "msgs"))
                                  msgs (keep (fn [m] (first (wire/decode m))) raw)]
                              (set! (.-msgs-in this) (+ (or (.-msgs-in this) 0) (count msgs)))
                              (-> (.ingest this (vec msgs))
                                  (.then (fn [_] (json {:ok true :n (count msgs)} 200)))))))
                   (.catch (fn [_] (json {:ok false :reason "bad-batch"} 400))))

               "/step"
               (-> (.step this)
                   (.then (fn [_] (json {:ok true :height (r/height (.-replica this))
                                         :committed (r/committed-height (.-replica this))} 200))))

               "/tx"
               (-> (.text request)
                   (.then (fn [t]
                            (set! (.-replica this) (r/submit (.-replica this) t))
                            (json {:ok true :queued true} 200))))

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
