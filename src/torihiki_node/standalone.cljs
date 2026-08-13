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

(defonce state
  (atom (r/replica {:witness me
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
                    :machine machine})))

(defonce registry (atom {}))
(defonce out-node (atom nil))
(defonce stats (atom {:msgs-in 0 :msgs-out 0 :blocks []}))

(defn- now [] (.getTime (js/Date.)))

(defn- ship! [outbox]
  (doseq [{:keys [msg]} outbox]
    (swap! stats update :msgs-out inc)
    (when-let [n @out-node] ((:broadcast! n) msg))
    (doseq [[_ s] @registry] (when (:send! s) ((:send! s) msg)))))

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

      "eth_call"
      (let [{:keys [to data]} (first params)]
        (if-let [out (evm/call ex to data)]
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
         (ship! out)))
     tick-ms)
    (watch-thorchain!)
    (println (str "torihiki " me " · peers " port " · http " (env "HTTP_PORT" "8801")
                  " · tick " tick-ms "ms"))))
