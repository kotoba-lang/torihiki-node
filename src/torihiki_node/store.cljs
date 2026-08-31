(ns torihiki-node.store
  "The chain's blocks, addressed by CID, on a store that is not this machine.

  ## What this deliberately is not

  An earlier version of this namespace put the blocks in Durable Object
  storage. That was wrong and the reason is written down already: superproject
  ADR-2608039000 forbids a single vendor's primitive as a PREMISE on a path
  that claims to be decentralised, and its test is to delete the store and ask
  whether the data is gone or merely slower to reach. Blocks in a Durable
  Object are gone. Worse, they are gone in a way that is invisible from
  outside: a CID is a global name, and a global name that only resolves inside
  one vendor's object is not one.

  So there is no Durable Object block store here, and no local-filesystem
  fallback either. A fallback is how a deployment ends up believing its blocks
  are replicated when they are on one disk.

  ## What it is

  `kotobase-storage-s3` already implements the whole contract against any
  S3-compatible endpoint -- Backblaze B2, Cloudflare R2, MinIO, AWS -- storing
  blocks under `blocks/<cid>`. This namespace does not reimplement that. It
  configures it, and it refuses to be configured badly.

  Blocks need no conditional write: `:immutable-blocks` and
  `:cid-addressed-read` are the whole requirement, and that is why B2 is a
  perfectly good block store while being a bad ref store. `kotobase.storage.s3`
  is honest about this -- it opens as `:single-writer-ref` unless an endpoint's
  conditional PUT has actually been probed -- and none of it matters here,
  because the chain head is not a ref this namespace publishes. That is
  `inga.ref`'s job and a separate piece of work.

  ## Not publishing is not the same as publishing

  Block publication is asynchronous and the local log is not: `standalone`'s
  `persist!` is `appendFileSync` because an append that lands out of order is
  a log that replays into a different chain. Publication cannot be put on that
  path without giving it a queue, and it must not be fire-and-forget either --
  a store that silently drops every block looks exactly like one that is
  working.

  So `publisher` counts. `published`, `pending` and `failed` are reported
  separately, the last error is kept, and an UNCONFIGURED publisher says so by
  name rather than reporting zero failures."
  (:require [ipld.core :as ipld]
            [ipld.link :as ilink]
            [kotobase.storage.core :as storage]
            [kotobase.storage.s3 :as s3]))

;; ── what a block IS, so it is one thing and not three ───────────────────────

(defn block-node
  "A block as the IPLD node its CID addresses.

  ## Why this is here and not in the deployment that first wrote it

  `validator.cljs` has an identical private copy. It is the Worker's, it works,
  and it cannot be required from a plain Node process without dragging a
  Durable Object namespace along -- so this is a duplication, stated rather
  than hidden, and the two should collapse onto this one the next time that
  file is opened. `block_test` pins the CID by value so a drift between them
  shows up as a changed digest rather than as two chains that disagree about
  what a block is.

  ## The fields are exactly what the hash already covered

  `inga.consensus/canonical-block` commits to height, parent, proposals,
  proposer and ts -- and NOT to the justify QC, which is a claim about the
  parent rather than about this block.

  ## The parent is a LINK, not a string

  Tag 42, which is what makes this a DAG any IPLD tool can walk rather than a
  chain whose blocks happen to contain a hash. It requires the chain's own
  `:hash-fn` to produce CIDs: a parent identified by some other digest cannot
  be a link, and `torihiki-node.standalone` used a bare SHA-256 hex until this
  landed, which is why its blocks could not be published as a DAG at all."
  [{:keys [inga.block/height inga.block/parent-hash inga.block/proposals
           inga.block/proposer inga.block/ts]}]
  {"height" height
   "parent" (when (and parent-hash (not= "genesis" parent-hash))
              (ilink/link parent-hash))
   "proposals" (vec proposals)
   "proposer" proposer
   "ts" ts})

(defn block-bytes [b] (ipld/encode (block-node b)))

(defn block-cid
  "CIDv1, dag-cbor, sha2-256 -- the block's identity."
  [b]
  (ipld/cid (block-bytes b)))

;; ── configuration, and refusing to guess ────────────────────────────────────

(def ^:private required
  "What an S3-compatible endpoint needs before it can hold a block.

  No defaults. A default endpoint is a deployment writing its chain somewhere
  nobody chose, and a default bucket is two chains sharing one."
  {:endpoint "TORIHIKI_BLOCKS_ENDPOINT"
   :bucket "TORIHIKI_BLOCKS_BUCKET"
   :region "TORIHIKI_BLOCKS_REGION"
   :access-key "TORIHIKI_BLOCKS_ACCESS_KEY"
   :secret-key "TORIHIKI_BLOCKS_SECRET_KEY"})

(defn config-from-env
  "`{:ok config}` or `{:unconfigured [missing-var ...]}`.

  Two shapes rather than nil, because a caller that got nil would have to
  decide what nil meant, and the answers it could reach for -- skip
  publication, publish nowhere, publish locally -- are all worse than saying
  which variables are missing.

  `getenv` is a FUNCTION from variable name to value, not a map, and that is
  not fussiness. `js/process.env` is a Proxy: `js->clj` leaves it alone rather
  than converting it, so a map built that way answers nil for every variable
  that is actually set. A deployment configured correctly was told it was
  unconfigured, and the only reason that was harmless is that this refuses in
  the safe direction. Taking a lookup function makes the read the caller's,
  where it can be `aget` on exactly the five names this asks for -- which is
  also the only way to read an environment without enumerating it."
  [getenv]
  (let [missing (vec (sort (keep (fn [[_ v]] (when (empty? (getenv v)) v)) required)))]
    (if (seq missing)
      {:unconfigured missing}
      {:ok (into {} (for [[k v] required] [k (getenv v)]))})))

(defn block-store
  "An S3-compatible block store.

  `:conditional-put` is left at the library's `:unverified` default on
  purpose. It decides the REF profile and this store publishes no refs, so
  claiming `:verified` here would be asserting something about an endpoint
  nobody probed in order to change a value nothing reads."
  [config]
  (s3/open {:client (s3/signed-client (select-keys config
                                                   [:endpoint :bucket :region
                                                    :access-key :secret-key :fetch]))
            :prefix (:prefix config)}))

;; ── publication, with the counters that make it honest ──────────────────────

(defn publisher
  "A bounded, counted publisher over a block store.

  `store` may be nil, and that is the UNCONFIGURED state rather than an error:
  a validator with no block store still runs, still has a local log, and must
  say plainly that its blocks are going nowhere."
  ([] (publisher nil nil))
  ([store unconfigured]
   (atom {:store store :unconfigured unconfigured
          :published 0 :pending 0 :failed 0 :last-error nil})))

(defn publish!
  "Publish one block's bytes under its CID. Returns a Promise that always
  resolves -- a rejection here would take down the caller's tick, and the
  caller is the thing keeping the chain moving.

  The failure is not swallowed: it is counted and the message kept."
  [pub cid bytes]
  (let [{:keys [store]} @pub]
    (if-not store
      (js/Promise.resolve :unconfigured)
      (do (swap! pub update :pending inc)
          (-> (storage/-put-blocks! store [{:cid cid :bytes bytes}])
              (.then (fn [_]
                       (swap! pub #(-> % (update :pending dec) (update :published inc)))
                       :published))
              (.catch (fn [e]
                        (swap! pub #(-> % (update :pending dec) (update :failed inc)
                                        (assoc :last-error (str (.-message e)))))
                        :failed)))))))

(defn status
  "What to report. `:blocks` is `\"unconfigured\"` and NOT a zero count when
  there is no store -- a deployment reading `failed 0` off an unconfigured
  publisher would be reading the absence of attempts as the absence of
  problems."
  [pub]
  (let [{:keys [store unconfigured published pending failed last-error]} @pub]
    (if-not store
      {:blocks "unconfigured"
       :missing unconfigured
       :note "blocks are in the local log only; nothing outside this machine can resolve a CID"}
      {:blocks "configured"
       :published published :pending pending :failed failed
       :last-error last-error})))

;; ── the sync/async seam ─────────────────────────────────────────────────────

(defrecord PromisedRefStore [inner]
  storage/IRefStore
  (-read-ref [_ ref-name]
    (js/Promise.resolve (storage/-read-ref inner ref-name)))
  (-compare-and-set-ref! [_ ref-name expected next]
    (js/Promise.resolve (storage/-compare-and-set-ref! inner ref-name expected next)))
  storage/IBackendCapabilities
  ;; Echoed, never widened. Wrapping a `:single-writer-ref` in a Promise does
  ;; not make it linearizable, and a wrapper that upgraded the claim would make
  ;; the contract's concurrent half run against a backend that never offered to
  ;; pass it.
  (-capabilities [_] (storage/-capabilities inner)))

(defn promised-ref
  "A synchronous `IRefStore` usable where the contract expects Promises.

  `kotobase.storage.async-contract` calls `.then` on whatever a store returns,
  and the ref store this chain will eventually publish through is portable
  `.cljc` that returns values: `inga.ref` is pure by design -- no I/O, no
  crypto, no wall-clock, every seam injected -- and that purity is what lets a
  browser verify a head without trusting a validator.

  So the mismatch is not a defect on either side, and the fix is not to make
  `inga.ref` async. It is one adapter, here, in the host that needs it."
  [inner]
  (when-not (and (storage/ref-store? inner)
                 (satisfies? storage/IBackendCapabilities inner))
    (throw (ex-info "torihiki-node.store: not a ref store"
                    {:type :torihiki-node.store/invalid-ref-store})))
  (->PromisedRefStore inner))
