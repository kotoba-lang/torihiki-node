(ns torihiki-node.worker
  "The transport: a Cloudflare Worker in front of a Durable Object sequencer.

  ## Why a Durable Object

  `torihiki.log` needs exactly one writer. Cloudflare guarantees one instance
  of a Durable Object per id, single-threaded — so 'there is exactly one
  writer' comes from the platform instead of being implemented with a write
  lease and a fencing epoch, which is the kind of thing that looks right in a
  design document and loses money in production.

  This is a SEQUENCER, not consensus. One writer decides the order; nothing
  votes. `/head` says so in its own response, because a service that is quiet
  about it will be assumed to be the other thing.

  ## Why the engine, and not a reimplementation

  `/tx` runs `torihiki.state/apply-block` — the same compiled `.cljc` a JVM
  validator runs. The advanced-optimised bundle was checked against the JVM
  and produces identical state roots, so a client can replay this node's log
  and contradict it. A hand-written JavaScript order book here would make that
  impossible and would be a second implementation to keep in agreement forever.

  ## Durability

  The transaction log is appended to Durable Object storage and replayed on a
  cold start. The exchange itself is never serialised: replaying the log IS
  the state, so there is no second encoding to keep in sync. The cost is a
  cold start proportional to the log, and the honest limit is that a
  long-lived node needs snapshotting and does not have it yet."
  (:require [torihiki.state :as st]
            [torihiki.clearing :as cl]
            [torihiki.auth :as auth]
            [torihiki.api :as api]
            [torihiki.address :as addr]
            [torihiki.book :as bk]
            [torihiki-chart.candle :as cndl]))

(def ^:const chain-id "torihiki-devnet-1")

(def ^:const code-version
  "Bumped by hand on every deploy, and surfaced at `/head`.

  A Durable Object does not pick up a new Worker version when you deploy — it
  keeps executing the code it started with until it is evicted. For a
  sequencer holding state in memory that can last indefinitely under traffic,
  so \"deployed\" and \"running\" are different facts. This was learned the
  expensive way: twice in one session a fix was deployed, verified present in
  the bundle, and then contradicted by the live endpoint, which was still
  running the previous build. Without a marker there is nothing to check."
  "10")
(def ^:const market-id 1)

(def ^:const tape-size
  "Recent fills kept for `/trades`. A view, not history — `/candles` is where
  history lives."
  200)

(def ^:const candle-retention
  "How many BLOCK CANDLES the sequencer keeps.

  Only blocks that traded produce one, so this is a count of active blocks,
  not of height. At the devnet's rate it is hours of trading; on a quiet
  market it is much longer.

  ## Why they are not written to storage

  This file's own docstring says of the exchange: *replaying the log IS the
  state, so there is no second encoding to keep in sync.* Candles are a fold
  of the same log, and persisting them would be exactly that second encoding
  — one that can disagree with the log after a bad deploy, and that has to be
  migrated whenever the fold changes. Rebuilt on the cold start that already
  replays the log, they cannot disagree with it.

  What that costs, stated rather than hidden: an eviction loses candles older
  than the log's own horizon, and `/candles` says how far back it can actually
  see rather than implying it sees everything."
  10000)

(def market
  (assoc (cl/market {:id market-id :max-leverage 40 :tick 10 :lot 1})
         :taker-fee-rate 350000
         :maker-fee-rate 100000))

(defn genesis []
  (st/new-exchange {:market market
                    :book-opts {:n-levels 1048576 :cap 262144 :ev-cap 65536}}))

;; ── signatures ──────────────────────────────────────────────────────────────

(defn- b64->bytes [s]
  (let [bin (js/atob s)
        n (.-length bin)
        out (js/Uint8Array. n)]
    (dotimes [i n] (aset out i (.charCodeAt bin i)))
    out))

(defn- verify-one
  "A promise of true/false for one Ed25519 signature, via WebCrypto.

  This is the only place in the whole system that knows what Ed25519 is, and
  that is the point: `torihiki.auth` takes verification as a parameter so the
  engine stays runnable where there is no crypto to import.

  WebCrypto's verify is asynchronous and the engine's seam is synchronous, so
  the signature is checked BEFORE the block is applied and the injected
  function only consults the answer. Making the engine async to accommodate a
  platform API would have pushed a transport concern into consensus code."
  [pubkey payload sig]
  (-> (js/crypto.subtle.importKey
       "raw" (b64->bytes pubkey) #js {:name "Ed25519"} false #js ["verify"])
      (.then (fn [k]
               (js/crypto.subtle.verify
                #js {:name "Ed25519"} k (b64->bytes sig)
                (.encode (js/TextEncoder.) payload))))
      (.catch (fn [_] false))))

(defn- normalize-tx
  "JSON has no keywords, so `{\"tx\":\"order\"}` arrives as a string while
  `api/validate` dispatches on `:order`. Normalising here — BEFORE the signing
  payload is computed — is what keeps the client and the node hashing the same
  thing: both sides see keywords, so `(name :order)` agrees. Normalising after
  would give the two sides different payloads and every signature would fail
  for a reason that looks like cryptography and is not."
  [tx]
  (cond-> tx
    (string? (:tx tx)) (update :tx keyword)
    (string? (:direction tx)) (update :direction keyword)))

(defn- json-response
  ([x] (json-response x 200))
  ([x status]
   (js/Response. (js/JSON.stringify (clj->js x))
                 #js {:status status
                      :headers #js {"content-type" "application/json"
                                    "access-control-allow-origin" "*"}})))

;; ── the sequencer ───────────────────────────────────────────────────────────
;;
;; Two constructor fields, because that is what Cloudflare calls the class
;; with: `new Sequencer(state, env)`. Everything mutable hangs off the
;; instance and is initialised on first use rather than in the constructor —
;; the constructor cannot await the storage read that a cold start needs.

(deftype Sequencer [^js do-state ^js env]
  Object
  ;; Both views of the fills — the tape and the candles — from ONE place.
  ;;
  ;; They were not: the tape was built in `submit` only, so a cold start
  ;; replayed the log, rebuilt the candles, and left `/trades` EMPTY while
  ;; `/candles` showed history. Observed on the first deploy of `/candles`:
  ;; height 57, eight candles, zero trades. Two endpoints reading the same
  ;; fills disagreed about whether any had happened.
  ;;
  ;; The fold itself is `torihiki-chart.candle/absorb` — deliberately NOT
  ;; reimplemented here. The terminal draws candles from the same library, and
  ;; two folds that are supposed to agree eventually will not: when the chart
  ;; and the endpoint disagree there is no way to say which one is lying.
  (noteFills [this ex h]
    (let [fills (bk/fills (get-in ex [:books market-id]))]
      (when (seq fills)
        ;; The tape: a bounded ring of recent fills. The engine's event buffer
        ;; is reset every block, so a terminal that wanted a tape had nowhere
        ;; to read one — and replaying the log on every poll would make a read
        ;; cost what a cold start costs. A view, not history.
        (set! (.-tape this)
              (into (vec (take-last tape-size
                                    (concat (or (.-tape this) [])
                                            (map (fn [f] {:level (:level f) :qty (:qty f)
                                                          :side (:taker-side f) :h h})
                                                 fills))))
                    []))
        ;; One block, at most one candle.
        (let [c (reduce (fn [c f]
                          (cndl/absorb c {:level (:level f) :qty (:qty f)
                                          :side (cndl/normalize-side (:taker-side f))}))
                        nil fills)
              arr (or (.-candles this) (array))]
          (.push arr (assoc c :h h))
          (when (> (.-length arr) candle-retention) (.shift arr))
          (set! (.-candles this) arr)))))

  (ensureLoaded [this]
    (if (.-loaded this)
      (js/Promise.resolve nil)
      (-> (.list ^js (.-storage do-state) #js {:prefix "tx:"})
          (.then
           (fn [entries]
             ;; Replay the log. Signatures were verified when these
             ;; transactions were accepted; re-checking them would re-litigate
             ;; a decision this node already made and recorded, which is the
             ;; distinction `apply-block`'s two modes exist for.
             (let [vals (js/Array.from (.values entries))
                   txs (map #(update (js->clj (js/JSON.parse %) :keywordize-keys true)
                                     :tx normalize-tx)
                            vals)
                   ;; Replay applies the SAME envelopes the live path applied,
                   ;; including the ones validation rejected — those spent a
                   ;; nonce, and a replay that skipped them would end up with a
                   ;; different nonce than the node had before the eviction.
                   ;; Authentication is not re-checked (see above), so the
                   ;; injected verifier accepts: these signatures were already
                   ;; verified when they were accepted.
                   [ex h] (reduce (fn [[e h] entry]
                                    (let [h (inc h)
                                          e' (st/apply-block e {:height h :ts (* h 1000)
                                                                :txs [entry]}
                                                             {:chain-id chain-id
                                                              :verify-fn (constantly true)})]
                                      ;; Candles are rebuilt HERE rather than
                                      ;; read back from storage — see
                                      ;; `candle-retention`.
                                      (.noteFills this e' h)
                                      [e' h]))
                                  [(genesis) 0]
                                  txs)]
               (set! (.-ex this) ex)
               (set! (.-height this) h)
               (set! (.-loaded this) true)
               nil))))))

  (submit [this body]
    (let [entry (update (js->clj body :keywordize-keys true) :tx normalize-tx)
          {:keys [tx account nonce pubkey sig]} entry]
      (if (or (nil? tx) (nil? sig) (nil? pubkey) (not (integer? account))
              (not (integer? nonce)))
        (js/Promise.resolve (json-response {:ok false :reason "malformed-envelope"} 400))
        (let [payload (auth/signing-payload chain-id account nonce tx)]
          (-> (verify-one pubkey payload sig)
              (.then
               (fn [ok?]
                 (let [h (inc (.-height this))
                       ex' (st/apply-block
                            (.-ex this)
                            {:height h :ts (* h 1000) :txs [entry]}
                            {:chain-id chain-id
                             :verify-fn (fn [_pk _payload _sig] (true? ok?))
                             ;; A key may only claim the account id derived
                             ;; from it. Without this, whoever gets a
                             ;; transaction in first owns the id — which under
                             ;; a single sequencer means whoever asks first,
                             ;; and under consensus means whoever orders the
                             ;; block. Existing bindings are untouched.
                             :derive-account addr/derive})
                       rejected (first (:rejected ex'))
                       authenticated? (not (contains? auth/reasons (:reason rejected)))]
                   ;; A transaction that authenticated and THEN failed
                   ;; validation still changed the state — it spent its nonce
                   ;; (ADR-2608020230). So it is kept AND logged, exactly like
                   ;; an accepted one.
                   ;;
                   ;; Logging only accepted transactions was a real bug and not
                   ;; a cosmetic one: the live state would have the nonce spent
                   ;; while a cold-start replay would not, so the node would
                   ;; disagree with itself across an eviction and the signature
                   ;; would become replayable by waiting.
                   ;;
                   ;; A transaction that failed AUTHENTICATION is dropped
                   ;; entirely, because it was never from the account and
                   ;; changed nothing.
                   (if (and rejected (not authenticated?))
                     (js/Promise.resolve
                      (json-response {:ok false :height (.-height this)
                                      :reason (name (:reason rejected))} 401))
                     (do
                       (set! (.-ex this) ex')
                       (set! (.-height this) h)
                       (.noteFills this ex' h)
                       (-> (.put ^js (.-storage do-state)
                                 (str "tx:" (.padStart (str h) 12 "0"))
                                 (js/JSON.stringify (clj->js entry)))
                           (.then (fn [_]
                                    (json-response
                                     (cond-> {:ok (nil? rejected) :height h
                                              :state-root (st/state-root ex')}
                                       rejected (assoc :reason (name (:reason rejected))))
                                     (if rejected 400 200)))))))))))))))

  (fetch [this ^js request]
    (let [url (js/URL. (.-url request))
          path (.-pathname url)
          q (fn [k] (.get (.-searchParams url) k))]
      (-> (.ensureLoaded this)
          (.then
           (fn [_]
             (let [ex (.-ex this)]
               (case path
                 "/tx" (if (= "POST" (.-method request))
                         (-> (.json ^js request) (.then #(.submit this %)))
                         (json-response {:ok false :reason "method"} 405))
                 "/book" (json-response
                          (api/book-snapshot ex (js/parseInt (or (q "market") "1"))
                                             (js/parseInt (or (q "depth") "15"))))
                 "/account" (json-response
                             (api/account-state ex (js/parseInt (or (q "id") "0"))))
                 "/market" (json-response
                            (api/market-info ex (js/parseInt (or (q "id") "1"))))
                 "/trades" (json-response
                            {:market market-id
                             :trades (vec (reverse (take-last
                                                    (js/parseInt (or (q "n") "20"))
                                                    (or (.-tape this) []))))})
                 ;; Block candles. `span` is in BLOCKS, and the response says
                 ;; so — the engine has no wall clock (`torihiki`'s README:
                 ;; logical time arrives in the block header), so a minute
                 ;; candle would have to be manufactured from a truth that
                 ;; does not exist here.
                 ;;
                 ;; Stored at one-block granularity and folded up on request,
                 ;; so the span is chosen by the caller rather than frozen at
                 ;; write time. `rebucket`'s boundaries are absolute, so this
                 ;; returns what folding the raw tape would have returned.
                 "/candles"
                 (let [span (max 1 (or (js/parseInt (or (q "span") "10")) 1))
                       n (max 1 (min 1000 (or (js/parseInt (or (q "n") "200")) 200)))
                       arr (vec (or (.-candles this) []))
                       floor (when (seq arr)
                               (- (cndl/bucket span (:h (peek arr)))
                                  (* span (dec n))))
                       ;; Only the tail the caller asked for is folded. The
                       ;; cut is on a bucket boundary, so the oldest candle
                       ;; returned is whole rather than a fragment of one.
                       window (if floor (filterv #(>= (:h %) floor) arr) [])
                       cs (cndl/rebucket span window)]
                   (json-response
                    {:market market-id
                     :span span
                     :unit "blocks"
                     :candles cs
                     ;; What this node can actually see. Candles live in
                     ;; memory and are rebuilt by the cold-start replay, so
                     ;; the horizon is real and stating it is the difference
                     ;; between a gap and a lie.
                     :retained-from (when (seq arr) (:h (first arr)))
                     :retained-blocks (count arr)
                     :retention candle-retention
                     ;; True when the window asked for starts before anything
                     ;; this node can see. WHY it cannot see further is a
                     ;; different fact and is reported separately — a full
                     ;; ring means retention dropped the rest, a short one
                     ;; means there was never more to drop (a young node, or
                     ;; one whose log does not reach back that far). One
                     ;; boolean covering both reads as data loss every time a
                     ;; fresh node is asked a wide question.
                     :truncated (boolean (and floor (> (:h (first arr)) floor)))
                     :truncated-because
                     (when (and floor (> (:h (first arr)) floor))
                       (if (>= (count arr) candle-retention)
                         "retention"
                         "no-older-blocks"))}))
                 "/head" (json-response
                          {:chain-id chain-id
                           :code-version code-version
                           :height (.-height this)
                           :state-root (st/state-root ex)
                           :resting (bk/resting-count (get-in ex [:books market-id]))
                           ;; stated in the response on purpose — a service
                           ;; that stays quiet about this will be assumed to
                           ;; be the other thing
                           :consensus "none — single sequencer, not a validator set"
                           :account-ids "derived from the key (torihiki.address)"
                           ;; Every margin, liquidation and ADL number this
                           ;; node produces is exact arithmetic over
                           ;; collateral. The engine can name a bridge
                           ;; authority so that only it may credit an account;
                           ;; this devnet deliberately names none, so any
                           ;; account credits itself. Stated for the same
                           ;; reason :consensus is: it will otherwise be
                           ;; assumed to be the other thing.
                           :bridge-authority (:bridge-authority ex)
                           :collateral (if (:bridge-authority ex)
                                         "bridged"
                                         "unbacked — any account may credit itself (devnet faucet)")})
                 (json-response {:ok false :reason "not-found"} 404)))))))))

(def handler
  #js {:fetch
       (fn [^js request ^js env _ctx]
         (if (= "OPTIONS" (.-method request))
           (js/Response. nil
                         #js {:headers #js {"access-control-allow-origin" "*"
                                            "access-control-allow-methods" "GET,POST,OPTIONS"
                                            "access-control-allow-headers" "content-type"}})
           (let [^js ns* (.-SEQUENCER env)
                 id (.idFromName ns* "main")]
             (.fetch (.get ns* id) request))))})
