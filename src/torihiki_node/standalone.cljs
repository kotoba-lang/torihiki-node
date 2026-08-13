;; The validator as an ordinary process.
;;
;;   W=w1 PEERS=w1@ws://127.0.0.1:19401,w2@ws://127.0.0.1:19402,... \
;;   HTTP_PORT=8801 nbb -cp "src:<deps>" -m torihiki-node.standalone
;;
;; ## Why this exists, in one measurement
;;
;; The same engine — `inga.replica` taking `torihiki.state` through its machine
;; seam — was run on two substrates and timed:
;;
;;   this shape (4 replicas, real sockets)   601 blocks / 20 s  =  33 ms
;;   Cloudflare Durable Objects (deployed)                        184-211 ms
;;
;; and the harness that produced 33 ms ticked every 120 ms, five times SLOWER
;; than the deployed clock. Progress is driven by messages, not by the clock:
;; counted on the deployment, `propose-on-msg 10-12` against `propose-on-tick
;; 0`. Every proposal there is already event-driven and it still takes 200 ms,
;; because a Durable Object serialises what arrives — a message queues behind
;; whatever the object is doing, and it is always doing something.
;;
;; So the gap to Hyperliquid's ~70 ms was never the engine, the transport, the
;; block interval or the commit rule; each of those was measured and cleared.
;; It is one property of the substrate, and this file is the substrate without
;; it. 33 ms is already under the target.
;;
;; ## Where it got to (2026-08-13)
;;
;; Four processes, one host, the real exchange with two markets:
;;
;;   all four replicas, identical block hash at height 500
;;   heights 1038-1047 and climbing
;;   block  min 1-2 ms   p50 7-8 ms   max 93-96 ms   (256 samples each)
;;
;; **7-8 ms at the median against 184-211 ms on Durable Objects**, and about
;; nine times under Hyperliquid's ~70 ms.
;;
;; The first run stopped at height 7 with every peer `:dropped` and
;; `failures 0` — nothing had failed to CONNECT, everything had failed to
;; parse. `:hash-fn` was `wire/wire-id` applied to the canonical block, a
;; function for witness names that does not return a hash; the ids it made
;; travelled inside votes and certificates, `inga.wire/decode` refused every
;; message carrying one, and `:dropped` is terminal. A transport that cannot
;; say which of those two it is costs a day, so `/head` now reports each
;; peer's state, failure count and next attempt.
;;
;; ## What it is NOT
;;
;; Not a replacement for the Worker yet. The deployed chain has persistence,
;; snapshots, quorum state transfer, subscriber sockets and an admin surface
;; that this does not; those are transport and operations concerns and they
;; port. What this establishes is the number, on the real exchange rather than
;; a stand-in machine, in a program that can run on any host.
(ns torihiki-node.standalone
  (:require ["ws" :as ws]
            ["node:crypto" :as nc]
            ["node:fs" :as fs]
            ["node:http" :as http]
            ["@noble/hashes/sha2.js" :refer [sha256]]
            [clojure.string :as str]
            [inga.consensus :as c]
            [inga.net.server :as srv]
            [inga.net.ws :as nws]
            [inga.replica :as r]
            [inga.wire :as wire]
            [torihiki.api :as api]
            [torihiki.auth :as auth]
            [torihiki.clearing :as cl]
            [torihiki.evm :as evm]
            [torihiki.evm.interp :as evmi]
            [torihiki.keccak :as kc]
            [torihiki.snapshot :as tsnap]
            [torihiki.thorchain :as tc]
            [torihiki.state :as st]))

(defn- env [k d] (or (some-> js/process .-env (aget k)) d))

(def chain-id (env "CHAIN_ID" "torihiki-standalone-1"))

(def witnesses
  "The validator set, ordered. Leadership rotates through it, so the ORDER is
  consensus state and not a detail of configuration — every replica has to
  read the same list in the same order or they lead different rounds."
  (vec (str/split (env "WITNESSES" "w1,w2,w3,w4") #",")))

(def me (env "W" "w1"))

(def peer-url
  "`name -> ws://host:port`, parsed from PEERS=w1@ws://…,w2@ws://…"
  (into {} (for [p (str/split (env "PEERS" "") #",")
                 :when (str/includes? p "@")
                 :let [[n u] (str/split p #"@" 2)]]
             [n u])))

(def tick-ms
  "How often the clock runs. **10 by default, and it is a backstop here.**

  On the Worker this had a floor: a Durable Object alarm asked for 10 ms
  delivered a 42-57 ms median gap, worse than asking for 25. An interval in an
  ordinary process has no such floor, and it matters less anyway — proposals
  come from messages. What the clock does is time views out and re-send a vote
  nobody answered."
  (js/parseInt (env "TICK_MS" "10") 10))

;; ── keys ────────────────────────────────────────────────────────────────────

(def ^:private pkcs8-ed25519-prefix
  "The 16 bytes that turn a raw 32-byte Ed25519 seed into a PKCS8 key node
  accepts. Written out rather than pulled in: it is a constant of the format,
  and a dependency for sixteen bytes is a dependency to keep current."
  (js/Buffer.from "302e020100300506032b657004220420" "hex"))

(defn- key-from-seed [seed-hex]
  (let [seed (js/Buffer.from seed-hex "hex")
        der (js/Buffer.concat #js [pkcs8-ed25519-prefix seed])
        sk (nc/createPrivateKey #js {:key der :format "der" :type "pkcs8"})]
    {:private sk :public (nc/createPublicKey sk)}))

(defn- seed-for
  "A witness's seed. From `SEED_<w>` when it is set — that is how a real set is
  configured, one secret per host — and otherwise derived from the chain id
  and the name, which makes a local run reproducible without secrets lying
  around. A chain whose keys are derivable is a devnet and says so."
  [w]
  (or (env (str "SEED_" (str/upper-case w)) nil)
      (.toString (js/Buffer.from (sha256 (js/Buffer.from (str chain-id "/" w) "utf8")))
                 "hex")))

(def keys-of (into {} (for [w witnesses] [w (key-from-seed (seed-for w))])))

(defn- b64 [buf] (.toString buf "base64"))

(defn- pub-of [w]
  (b64 (.export (:public (get keys-of w)) #js {:format "der" :type "spki"})))

(defn- sign-as [w]
  (fn [payload]
    (b64 (nc/sign nil (js/Buffer.from payload "utf8") (:private (get keys-of w))))))

(defn- verify-fn [w payload sig]
  (try
    (nc/verify nil (js/Buffer.from payload "utf8")
               (nc/createPublicKey #js {:key (js/Buffer.from (pub-of (wire/wire-id w)) "base64")
                                        :format "der" :type "spki"})
               (js/Buffer.from sig "base64"))
    (catch :default _ false)))

;; ── the exchange ────────────────────────────────────────────────────────────

(defn derive-account
  "The only account id a public key may claim. Same rule as everywhere else in
  this workspace: 45 bits of SHA-256 above the reserved range, refused rather
  than silent on collision."
  [pubkey]
  (let [d (sha256 (js/Buffer.from pubkey "base64"))]
    (+ 100000 (mod (reduce (fn [acc i] (+ (* acc 256) (aget d i))) 0 (range 6))
                   35184372088832))))

(def markets
  [(assoc (cl/market {:id 1 :max-leverage 40 :tick 10 :lot 1})
          :taker-fee-rate 350000 :maker-fee-rate 100000 :symbol "BTC-PERP")
   (assoc (cl/market {:id 2 :max-leverage 25 :tick 10 :lot 1})
          :taker-fee-rate 500000 :maker-fee-rate 150000 :symbol "ETH-PERP")])

(defn genesis-exchange []
  ;; `:markets`, not `:market` plus `:list-market` for the rest.
  ;;
  ;; Listing a market on a running exchange gives it a book from the spec;
  ;; building the genesis that way here gave market 2 a book whose bitmap
  ;; slabs were absent and the first `state-root` walked into a null:
  ;; `Cannot read properties of null` out of `book/best`. Every book a
  ;; genesis declares gets `book-opts`, which is what `:markets` is for.
  (-> (st/new-exchange {:markets markets
                        :book-opts {:n-levels 65536 :cap 16384 :ev-cap 8192}})
      (as-> ex (reduce (fn [e m] (st/apply-tx e {:tx :oracle :market (:id m) :price 1000}))
                       ex markets))))

(defn- tx-verify [pubkey payload sig]
  (try
    (nc/verify nil (js/Buffer.from payload "utf8")
               (nc/createPublicKey #js {:key (js/Buffer.from pubkey "base64")
                                        :format "der" :type "spki"})
               (js/Buffer.from sig "base64"))
    (catch :default _ false)))

(defn- decode-tx [s]
  (let [m (js->clj (js/JSON.parse s) :keywordize-keys true)]
    ;; Normalised BEFORE the signing payload is computed, or the two sides
    ;; disagree about `:order` versus `"order"` and every signature fails for
    ;; a reason that looks like cryptography and is not.
    (update m :tx (fn [t] (cond-> t (string? (:tx t)) (update :tx keyword))))))

(def machine
  {;; A THUNK. The book is a struct of typed arrays, so a ready-made exchange
   ;; in this map would be ONE book shared by every replica in a process.
   :init-fn genesis-exchange
   :apply-fn (fn [ex block]
               ;; The block header is the clock. Nothing below may read a real
               ;; one: two replicas applying the same block at different wall
               ;; times would compute different funding and diverge.
               (st/apply-block ex {:height (:inga.block/height block)
                                   :ts (:inga.block/ts block)
                                   :txs (mapv decode-tx (:inga.block/proposals block))}
                               {:chain-id chain-id :verify-fn tx-verify
                                :derive-account derive-account}))
   :root-fn st/state-root})

;; ── the node ────────────────────────────────────────────────────────────────

(defn- replica-opts []
  {:witness me
                    :witnesses witnesses
                    :quorum (c/quorum-size (count witnesses))
                    ;; hex of SHA-256 over the canonical block.
                    ;;
                    ;; This was `wire/wire-id` applied to the canonical block,
                    ;; which is a function for witness NAMES and does not
                    ;; return a hash — the ids it produced travelled inside
                    ;; votes and certificates, `inga.wire/decode` refused every
                    ;; message carrying one, and after enough refusals each
                    ;; peer was marked `:dropped`, which is terminal. All four
                    ;; replicas sat at height 7 with `failures 0` and every
                    ;; peer dropped: nothing had failed to CONNECT, everything
                    ;; had failed to parse.
                    :hash-fn (fn [b]
                               (.toString (js/Buffer.from
                                           (sha256 (js/Buffer.from (c/canonical-block b) "utf8")))
                                          "hex"))
                    :chain-id chain-id
                    :sign-fn (sign-as me)
                    :verify-fn verify-fn
                    :machine machine})

;; One definition of what this replica IS. `resume` needs exactly the options
;; `replica` took — the injected seams are functions and a snapshot cannot
;; carry them, so the caller puts them back. Two copies of this map would be
;; two definitions: a different hash-fn or machine on the resume path is a
;; replica that agrees on the order and disagrees on the result, which every
;; number about it would hide.
(defonce state (atom (r/replica (replica-opts))))

(defonce registry (atom {}))
(defonce out-node (atom nil))
(defonce stats (atom {:msgs-in 0 :msgs-out 0 :blocks []}))

(defn- now [] (.getTime (js/Date.)))

(defn- ship!
  "Send the outbox, and send each message to WHO IT IS FOR.

  This broadcast everything. `inga.replica` has always set `:to` and it was
  being ignored, which is invisible for votes and proposals — they go to
  everybody anyway — and fatal for sync: a replica that has fallen behind
  receives mostly the ANSWERS TO OTHER REPLICAS, each segment starting at a
  height it cannot reach, and refuses them one after another.

  Measured here, four replicas on one host: w3 sat at height 257 while the
  others ran at 409, with all three peers connected, **13,379 messages
  received** and nothing adopted. Being behind was what kept it behind. The
  Worker's own dispatch carries this note already; the standalone was written
  without it.

  Only the dialled sockets. Every replica dials every peer, so those reach
  everyone; also pushing down the inbound sessions sent each message twice."
  [outbox]
  (doseq [{:keys [msg to]} outbox]
    (swap! stats update :msgs-out inc)
    (when-let [n @out-node]
      (if (or (nil? to) (= :all to))
        ((:broadcast! n) msg)
        ((:send! n) to msg)))))

(defn- note-height! [h]
  ;; The block interval, from the inside, the same way the Worker reports it —
  ;; so the two substrates are compared on the same number and not on two
  ;; definitions of "a block".
  (swap! stats
         (fn [s]
           (if (= h (:last-height s))
             s
             (-> s
                 (assoc :last-height h :last-height-at (now))
                 (update :blocks
                         (fn [v] (let [v (or v [])]
                                   (if-let [t (:last-height-at s)]
                                     (vec (take-last 256 (conj v (- (now) t))))
                                     v)))))))))

(defn- feed! [msg]
  (swap! stats update :msgs-in inc)
  (let [[s' out] (r/on-message @state msg (now))]
    (reset! state s')
    (note-height! (r/height s'))
    ;; Sent HERE, as the message is folded, not on the next tick. On the
    ;; Worker this was the difference between a block every 250 ms and a block
    ;; every 150: a reply that waits for a clock adds a clock to every hop.
    (ship! out)))

(defn- quantiles [v]
  (when (seq v)
    (let [s (vec (sort v))]
      {:min (first s) :p50 (nth s (quot (count s) 2)) :max (peek s) :n (count s)})))

;; ── HTTP ────────────────────────────────────────────────────────────────────

(defn- json-response [res code body]
  (.writeHead res code #js {"Content-Type" "application/json"
                            "Access-Control-Allow-Origin" "*"})
  (.end res (js/JSON.stringify (clj->js body))))

(defn- read-body [req]
  (js/Promise.
   (fn [resolve _]
     (let [chunks (atom "")]
       (.on req "data" (fn [c] (swap! chunks str c)))
       (.on req "end" (fn [] (resolve @chunks)))))))

;; ── JSON-RPC ────────────────────────────────────────────────────────────────

(def ^:private zero-address "0x0000000000000000000000000000000000000000")

(def ^:const evm-chain-id
  "The chain id an EVM caller sees. Distinct from `chain-id`, which is this
  chain's own name and is not a number — an EIP-155 signature covers a NUMBER,
  and reusing a string there is how a transaction signed for one chain becomes
  replayable on another."
  1337)

(defn- rpc-result [id v]
  {:jsonrpc "2.0" :id id :result v})

(defn- rpc-error [id code message]
  {:jsonrpc "2.0" :id id :error {:code code :message message}})

(defn- hex-quantity
  "A number as an EVM RPC quantity: `0x`-prefixed, no leading zeroes, and `0x0`
  for zero. Not the same encoding as a 32-byte word — a caller that pads a
  quantity gets rejected by strict clients, which is the kind of thing that
  works in curl and fails in ethers."
  [n]
  (str "0x" (.toString (js/Number n) 16)))

(defn- rpc-call
  "One JSON-RPC request against the exchange.

  The read surface only, and the methods a caller actually needs to reach the
  precompile: what chain this is, how far along it is, and `eth_call`. A
  method that is not here answers `method not found` rather than something
  shaped like a result — a node that invents answers for methods it does not
  implement is worse than one that admits the gap, because a library will
  believe it."
  [state* {:keys [id method params]}]
  (let [s @state*
        ex (:machine-state s)]
    (case method
      "eth_chainId" (rpc-result id (hex-quantity evm-chain-id))
      "net_version" (rpc-result id (str evm-chain-id))
      "eth_blockNumber" (rpc-result id (hex-quantity (r/height s)))

      "eth_getCode"
      (rpc-result id (str "0x" (get-in ex [:evm (some-> (first params) str/lower-case) :code] "")))

      "eth_call"
      (let [{:keys [to data]} (first params)
            to (some-> to str/lower-case)]
        (if-let [out (or (evm/call ex to data)
                         ;; Deployed bytecode, from CHAIN state — `:evm` is in
                         ;; the state root, so two replicas answering this
                         ;; question differently is a disagreement the root
                         ;; shows. A world kept in the node would have made it
                         ;; invisible.
                         (when-let [code (get-in ex [:evm to :code])]
                           (let [world (into {} (for [[a c] (:evm ex)]
                                                  [a {:code (kc/hex->bytes (:code c))}]))
                                 r (evmi/run ex world
                                             {:address to :caller zero-address :depth 0}
                                             (kc/hex->bytes code) data)]
                             (when (= :return (:status r)) (str "0x" (:data r))))))]
          (rpc-result id out)
          ;; `execution reverted` rather than a zero word, and for the reason
          ;; `torihiki.evm/call` returns nil: an answer of zeroes to a
          ;; question the exchange never understood is indistinguishable from
          ;; a real zero, and the caller has no way to find out which it got.
          (rpc-error id 3 "execution reverted")))

      ;; A balance in wei is not collateral in ticks and pretending otherwise
      ;; would put a number in front of every wallet that means something
      ;; else. The precompile is where collateral is read.
      "eth_getBalance" (rpc-result id "0x0")

      (rpc-error id -32601 (str "method not found: " method)))))

(defn- handle-http [^js req ^js res]
  (let [url (js/URL. (.-url req) "http://x")
        path (.-pathname url)
        q (fn [k] (.get (.-searchParams url) k))
        s @state
        ex (:machine-state s)]
    (case path
      "/head"
      (json-response res 200
                     {:witness me
                      :height (r/height s)
                      ;; `:committed` is the list of committed blocks, not a height —
                      ;; reading it as one reported `null` on a chain that was
                      ;; committing every block.
                      :committed (count (:committed s))
                      ;; The root AT the committed tip, which is the only root
                      ;; two replicas can be compared on: at their own tips
                      ;; they are legitimately at different heights and
                      ;; different roots, and comparing those says nothing.
                      :committed-root
                      (when-let [b (last (:committed s))]
                        {:height (:inga.block/height b)
                         :root (:inga.block/state-root b)})
                      :view (:view (:pm s))
                      :state-root (st/state-root ex)
                      :pending (count (:pending s))
                      :msgs-in (:msgs-in @stats)
                      :msgs-out (:msgs-out @stats)
                      :inbound (count @registry)
                      ;; Which peers this replica can actually reach, from the
                      ;; driver's own state — not a count of sockets that were
                      ;; once opened. A replica talking to nobody and a replica
                      ;; whose peers are quiet look identical from the outside,
                      ;; and the first run could not tell them apart.
                      :live-peers (vec (map str (some-> @out-node :live (apply []))))
                      :peer-status (into {} (for [[k v] (:peers @(:state @out-node))]
                                              [(str k) {:state (str (:state v))
                                                        :failures (:failures v)
                                                        :next-in (when (:next-attempt v)
                                                                   (- (:next-attempt v) (now)))}]))
                      :block-ms (quantiles (:blocks @stats))
                      :substrate "process"})

      ;; The block hash at a given height, which is how two replicas are
      ;; compared. A block header carries no state root here — `canonical-block`
      ;; never had one — so "same root" is established the way consensus
      ;; establishes it: same chain, deterministic machine.
      "/hash-at"
      (let [h (js/parseInt (or (q "h") "0") 10)
            b (first (filter #(= h (:inga.block/height %)) (:chain s)))]
        (json-response res 200 {:height h
                                :hash (when b ((:hash-fn s) b))
                                :have (count (:chain s))}))

      "/rpc"
      (-> (read-body req)
          (.then (fn [body]
                   (let [j (js->clj (js/JSON.parse body) :keywordize-keys true)]
                     (json-response res 200
                                    (if (vector? j)
                                      (mapv #(rpc-call state %) j)
                                      (rpc-call state j)))))))

      "/tx"
      (-> (read-body req)
          (.then (fn [body]
                   (swap! state r/submit body)
                   (json-response res 200 {:ok true}))))

      "/book"
      (json-response res 200 (api/book-snapshot ex (js/parseInt (or (q "m") "1") 10)))

      "/account"
      (json-response res 200 (api/account-state ex (js/parseInt (q "a") 10)))

      "/markets"
      (json-response res 200 {:markets (mapv #(api/market-info ex (:id %)) markets)})

      (json-response res 404 {:ok false :reason "no-such-route"}))))

;; ── start ───────────────────────────────────────────────────────────────────

;; ── the log ─────────────────────────────────────────────────────────────────

(def data-dir (env "DATA_DIR" (str ".torihiki/" me)))

(def ^:private log-path (str data-dir "/chain.jsonl"))

(defonce persisted (atom 0))

(defn- persist!
  "Append every block adopted since the last write.

  Cheap because the chain only grows: a block already in the log is a block
  this replica already decided to keep. The encoding is the Worker's — a
  `wire`-encoded proposal, one JSON object per line — so the two deployments
  write the same bytes for the same block and a log written by one can be read
  by the other.

  Synchronous. The volume is one small line per block and the ordering is the
  whole point: an append that lands out of order is a log that replays into a
  different chain. An async writer would need its own queue to promise what
  `appendFileSync` promises for free."
  []
  (let [chain (:chain @state)
        from @persisted]
    (when (< from (count chain))
      (let [new (subvec chain (min from (count chain)))]
        (fs/appendFileSync
         log-path
         (str (str/join "\n" (for [b new]
                                (js/JSON.stringify
                                 (clj->js (wire/encode {:type :proposal :block b})))))
              "\n"))
        (reset! persisted (count chain))))))

(def ^:const checkpoint-every
  "How many blocks between checkpoints. **500.**

  The log alone is enough to come back, and it is the wrong thing to come back
  with once it is long: replay is O(chain), and a startup that grows without
  bound is a restart that eventually does not finish. On the Worker that is
  not a slow boot but a killed one — a Durable Object exceeding its CPU budget
  is RESET, so it boots, replays, exceeds it again, and never starts."
  500)

(def ^:const checkpoints-kept
  "How many to keep. **8**, and the number is not for redundancy.

  A replica repairing itself from peers needs a height it and they both hold,
  and a replica far enough behind to need repairing is exactly the one whose
  window has stopped overlapping. Two was measured to be too few on the
  deployment: the one replica that needed the state was the reason nobody
  could give it any."
  8)

(defonce last-ckpt (atom -1))

(defn- ckpt-path [h]
  (str data-dir "/snap-" (.padStart (str h) 12 "0") ".edn"))

(defn- checkpoint!
  "The replica as data, so a restart does not have to fold the whole log.

  TWO snapshots compose here and each covers what the other cannot.
  `inga.replica/snapshot` bounds the consensus state — a tail of the chain,
  the certificates naming it, the pacemaker, and the `:voted-below` watermark
  that keeps a resumed replica from voting twice. Its `:machine-state` it
  carries whole, and this machine is an exchange holding `Book` records backed
  by typed arrays, which is not data and does not serialise.
  `torihiki.snapshot/capture` turns that into plain data whose
  `canonical-bytes` are byte-identical on restore — the only equality that
  matters here, because two states can be `=` and encode differently, and it
  is the encoding a validator signs."
  []
  (let [s @state
        h (r/height s)]
    (when (and (pos? h) (zero? (mod h checkpoint-every)) (not= h @last-ckpt))
      (try
        (fs/writeFileSync (ckpt-path h)
                          (tsnap/write-string
                           (update (r/snapshot s) :machine-state tsnap/capture)))
        (reset! last-ckpt h)
        (doseq [f (drop checkpoints-kept
                        (sort (fn [a b] (compare b a))
                              (filter #(str/starts-with? % "snap-")
                                      (array-seq (fs/readdirSync data-dir)))))]
          (fs/unlinkSync (str data-dir "/" f)))
        (catch :default _
          ;; A checkpoint that fails is a slower restart, not a broken
          ;; replica. It must never take the tick with it.
          nil)))))

(defn- newest-checkpoint
  "The most recent checkpoint on disk, as `[height snapshot]`, or nil.

  Newest FIRST and the first one that reads wins. A checkpoint that will not
  parse is skipped rather than fatal — it is one slower start, and the older
  ones behind it are exactly what `checkpoints-kept` is for."
  []
  (when (fs/existsSync data-dir)
    (some (fn [f]
            (try
              (let [h (js/parseInt (subs f 5 (- (count f) 4)) 10)
                    snap (tsnap/read-string* (fs/readFileSync (str data-dir "/" f) "utf8"))]
                [h (update snap :machine-state tsnap/restore)])
              (catch :default _ nil)))
          (sort (fn [a b] (compare b a))
                (filter #(str/starts-with? % "snap-")
                        (array-seq (fs/readdirSync data-dir)))))))

(defn- restore!
  "Replay the log into the replica.

  `r/replay` is what restores the chain, the certificates and the heights this
  replica already voted at — and, since the fix that closed the deploy-stall,
  the VIEW its own tip was proposed in. Without the log a restarted replica
  comes back at genesis and, if it leads, proposes a fresh block for a height
  it has already voted at. That is equivocation committed by accident against
  itself, and it is what four validators on Cloudflare did before they had
  one.

  A line that will not decode stops the replay rather than being skipped. The
  blocks after a hole do not attach to anything, so continuing would build a
  chain this replica never agreed to out of the pieces of one it did."
  []
  (fs/mkdirSync data-dir #js {:recursive true})
  ;; The checkpoint first, then only the log ABOVE it.
  ;;
  ;; Replaying from zero is correct and gets slower every block; the point of
  ;; a checkpoint is that everything at or below it is already folded in.
  ;; `resume` also leaves the `:voted-below` watermark, which refuses at least
  ;; as much as the folded set did — refusing more costs a vote at a height
  ;; already decided, refusing less is equivocation.
  (let [[ck snap] (newest-checkpoint)]
    (when snap
      (swap! state (fn [_] (r/resume (replica-opts) snap)))
      (reset! last-ckpt ck)
      (println (str "  resumed from checkpoint " ck)))
    (when (fs/existsSync log-path)
      (let [lines (remove str/blank? (str/split (fs/readFileSync log-path "utf8") #"\n"))
            blocks (reduce (fn [acc l]
                             (let [[msg] (wire/decode (js->clj (js/JSON.parse l)))]
                               (if-let [b (:block msg)]
                                 (conj acc b)
                                 (reduced acc))))
                           [] lines)
            above (filterv #(> (:inga.block/height %) (or ck 0)) blocks)]
        (when (seq above)
          (swap! state r/replay above))
        ;; `persisted` counts LINES already written, not blocks folded — the
        ;; log still holds everything below the checkpoint and appending from
        ;; the chain's count would write those heights a second time.
        (reset! persisted (count blocks))
        (println (str "  restored " (count above) " blocks above it, height "
                      (r/height @state)))))))

;; ── the escrow watcher ──────────────────────────────────────────────────────

(defonce watcher-nonce (atom 0))

(defn- attest! [tx]
  (let [acct (derive-account (pub-of me))
        nonce (swap! watcher-nonce inc)
        tx (assoc tx :account acct)
        payload (auth/signing-payload chain-id acct nonce tx)
        env {:tx tx :account acct :nonce nonce
             :pubkey (pub-of me)
             :sig ((sign-as me) payload)}]
    (swap! state r/submit (js/JSON.stringify (clj->js env)))))

(defn- watch-thorchain!
  "Poll THORChain and attest what this validator sees.

  Nothing here decides anything: `torihiki.thorchain` says which observations
  are usable and `torihiki.state` decides when enough validators agree. This
  is the network in between, and it is separate from both for the reason the
  namespace docstring gives — the part that needs a network to run is the part
  that cannot be tested, so it holds as little as possible.

  Off unless `THOR_VAULT` is set. A watcher with no vault to watch would poll
  a public endpoint forever and attest nothing, which is a cost with no
  answer."
  []
  (when-let [vault (env "THOR_VAULT" nil)]
    (let [base (env "THOR_URL" "https://thornode.ninerealms.com")
          every (js/parseInt (env "THOR_POLL_MS" "6000") 10)]
      (println (str "  escrow: watching " vault " via " base))
      (js/setInterval
       (fn []
         (-> (js/fetch (str base "/thorchain/lastblock"))
             (.then #(.json %))
             (.then (fn [j]
                      (let [tip (js/parseInt (or (some-> (aget j 0) (aget "thorchain"))
                                                 (aget j "thorchain") "0") 10)]
                        (-> (js/fetch (str base "/thorchain/queue/outbound"))
                            (.then #(.json %))
                            (.then (fn [obs]
                                     (let [os (js->clj obs)]
                                       (doseq [tx (tc/deposits-in
                                                   (derive-account (pub-of me))
                                                   vault tip os)]
                                         (attest! tx))
                                       (doseq [tx (tc/payouts-in
                                                   (derive-account (pub-of me))
                                                   tip os)]
                                         (attest! tx)))))))))
             (.catch (fn [_]
                       ;; A poll that failed is a poll that failed. It is not
                       ;; evidence of anything and must not become an
                       ;; attestation, so there is nothing to do but try again.
                       nil))))
       every))))

(defn -main [& _]
  (let [port (js/parseInt (second (str/split (get peer-url me "x:0") #":(?=[0-9]+$)")) 10)
        wss (ws/WebSocketServer. #js {:port port})
        n (atom 0)]
    (.on wss "connection"
         (fn [sock]
           (let [peer (str "in-" (swap! n inc))
                 handle (srv/attach! registry peer sock
                                     {:add-listener (fn [s ev f] (.on s ev f))
                                      :on-message (fn [_ m] (feed! m))})]
             (swap! registry update peer merge handle))))
    ;; BEFORE dialling. A replica that starts talking at genesis while its own
    ;; history is still on disk is a replica proposing against a chain it is
    ;; about to discover it already has.
    (restore!)
    (reset! out-node
            (nws/make-node {:peers (vec (remove #{me} witnesses))
                            :url-of peer-url
                            :on-message (fn [_ m] (feed! m))}))
    (.listen (http/createServer handle-http)
             (js/parseInt (env "HTTP_PORT" "8801") 10))
    ;; The height-1 leader bootstraps. Genesis has no certificate to extend, so
    ;; it is the one proposal made without one — every other replica waits for
    ;; it rather than proposing its own, which is what stops four replicas from
    ;; each starting a different chain.
    (when (= me (c/leader-for witnesses 1))
      (let [[s' out] (r/start @state (now))]
        (reset! state s')
        (ship! out)))
    (js/setInterval
     (fn []
       (when-let [nd @out-node] ((:tick! nd)))
       (let [[s' out] (r/on-tick @state (now))]
         (reset! state s')
         (note-height! (r/height s'))
         (ship! out)
         (persist!)
         (checkpoint!)))
     tick-ms)
    (watch-thorchain!)
    (println (str "torihiki " me " · peers " port " · http " (env "HTTP_PORT" "8801")
                  " · tick " tick-ms "ms"))))
