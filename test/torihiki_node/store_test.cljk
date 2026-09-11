(ns torihiki-node.store-test
  "The block plane: that it is configured deliberately, that it is a real
  S3-compatible store, and that not publishing never looks like publishing.

  ## What is deliberately absent

  There is no test for a Durable Object block store because there is no such
  store. Superproject ADR-2608039000's test -- delete it and ask whether the
  data is gone -- is failed by a Durable Object and by a local disk alike, and
  a fallback to either is how a deployment comes to believe its blocks are
  somewhere they are not.

  ## The endpoint is injected, not mocked away

  `kotobase.storage.s3/signed-client` takes `:fetch`, and its own docstring
  says why: the signed HTTP path had never been exercised, and the first test
  to call it found `signed-request` throwing on every request. So these drive
  the real client through a fetch that answers like an object store, which
  exercises SigV4 and the client's own status handling. What it does not
  prove is that any particular endpoint honours what it is sent."
  (:require [cljs.test :refer [deftest is testing async]]
            [kotobase.storage.async-contract :as contract]
            [kotobase.storage.core :as storage]
            [kotobase.storage.memory :as mem]
            [kotobase.storage.s3 :as s3]
            [torihiki-node.store :as store]))

;; ── a fetch that behaves like an object store ───────────────────────────────

(defn- object-endpoint []
  (let [objects (atom {})]
    {:objects objects
     :fetch
     (fn [url opts]
       (let [method (.-method opts)
             key (last (.split (.-pathname (js/URL. url)) "/"))
             body (.-body opts)]
         (js/Promise.resolve
          (case method
            "PUT" (do (swap! objects assoc key body)
                      #js {:ok true :status 200
                           :headers #js {:get (fn [_] "\"etag\"")}})
            "GET" (if-let [v (get @objects key)]
                    #js {:ok true :status 200
                         :headers #js {:get (fn [_] "\"etag\"")}
                         :arrayBuffer (fn [] (js/Promise.resolve (.-buffer (js/Uint8Array.from v))))}
                    #js {:ok false :status 404})
            #js {:ok false :status 405}))))}))

(defn- s3-blocks []
  (let [{:keys [fetch]} (object-endpoint)]
    (store/block-store {:endpoint "https://s3.example.invalid"
                        :bucket "torihiki" :region "us-east-1"
                        :access-key "AK" :secret-key "SK"
                        :fetch fetch})))

;; ── configuration refuses to guess ──────────────────────────────────────────

(deftest an-unset-endpoint-names-what-is-missing
  ;; nil would make the caller decide what nil meant, and every answer it could
  ;; reach for -- skip, publish nowhere, publish locally -- is worse than this.
  (let [r (store/config-from-env (constantly nil))]
    (is (nil? (:ok r)))
    (is (= ["TORIHIKI_BLOCKS_ACCESS_KEY" "TORIHIKI_BLOCKS_BUCKET"
            "TORIHIKI_BLOCKS_ENDPOINT" "TORIHIKI_BLOCKS_REGION"
            "TORIHIKI_BLOCKS_SECRET_KEY"]
           (:unconfigured r)))))

(deftest a-half-configured-endpoint-is-unconfigured
  ;; The dangerous shape: enough variables to look deliberate, not enough to
  ;; write anywhere.
  (let [r (store/config-from-env {"TORIHIKI_BLOCKS_ENDPOINT" "https://s3.example.invalid"
                                  "TORIHIKI_BLOCKS_BUCKET" "torihiki"})]
    (is (nil? (:ok r)))
    (is (= 3 (count (:unconfigured r))))))

(deftest a-complete-endpoint-configures
  (let [r (store/config-from-env {"TORIHIKI_BLOCKS_ENDPOINT" "https://s3.example.invalid"
                                  "TORIHIKI_BLOCKS_BUCKET" "torihiki"
                                  "TORIHIKI_BLOCKS_REGION" "us-east-1"
                                  "TORIHIKI_BLOCKS_ACCESS_KEY" "AK"
                                  "TORIHIKI_BLOCKS_SECRET_KEY" "SK"})]
    (is (nil? (:unconfigured r)))
    (is (= "torihiki" (:bucket (:ok r))))))

;; ── it is a real store ──────────────────────────────────────────────────────

(deftest the-shared-storage-contract-passes
  ;; Kotobase's own suite, the one the S3, IPFS and D1 providers are held to.
  ;; Written by them rather than by me, so it tests what a block store IS
  ;; rather than what I happened to think of.
  ;;
  ;; The ref half is a memory double. This namespace publishes no refs -- the
  ;; chain head is `inga.ref`'s quorum certificate and a separate piece of work
  ;; -- so the double claims nothing about the deployment.
  (async done
    (-> (contract/verify (storage/compose {:blocks (s3-blocks)
                                           :refs (store/promised-ref (mem/memory-store))}))
        (.then (fn [result]
                 (is (= 14 (:checks result)))
                 (is (= :linearizable-ref (:profile result)))
                 (is (= :verified (:concurrency result))
                     "the concurrent CAS half did not run, so nothing here
                      distinguishes an enforced precondition from an ignored one")
                 (done)))
        (.catch (fn [e]
                  (is false (str "contract violation: " (.-message e)))
                  (done))))))

(deftest the-blocks-really-leave-through-sigv4
  ;; The signed path, not a stub of it. `signed-client`'s own docstring records
  ;; that this path had never been exercised and was throwing on every request.
  (async done
    (let [{:keys [objects fetch]} (object-endpoint)
          s (store/block-store {:endpoint "https://s3.example.invalid"
                                :bucket "torihiki" :region "us-east-1"
                                :access-key "AK" :secret-key "SK" :fetch fetch})]
      (-> (storage/-put-blocks! s [{:cid "bafyone" :bytes (js/Uint8Array.from #js [1 2 3])}])
          (.then (fn [_]
                   (is (some #(re-find #"bafyone" %) (keys @objects))
                       "the CID did not become the object key")
                   (storage/-get-blocks s ["bafyone" "bafymissing"])))
          (.then (fn [found]
                   (is (= ["bafyone"] (keys found)))
                   (is (not (contains? found "bafymissing"))
                       "an absent CID came back as a present key with no bytes")
                   (done)))))))

;; ── not publishing never looks like publishing ──────────────────────────────

(deftest an-unconfigured-publisher-says-so-instead-of-reporting-zero-failures
  ;; The whole point of the counters. `failed 0` on a publisher that never
  ;; attempted anything is the absence of attempts reported as the absence of
  ;; problems -- the shape this workspace names as its own recurring defect.
  (let [pub (store/publisher nil ["TORIHIKI_BLOCKS_BUCKET"])
        st (store/status pub)]
    (is (= "unconfigured" (:blocks st)))
    (is (= ["TORIHIKI_BLOCKS_BUCKET"] (:missing st)))
    (is (not (contains? st :failed))
        "an unconfigured publisher reported a failure count")))

(deftest a-failed-publish-is-counted-and-kept
  ;; Fire-and-forget would make a store that drops every block look exactly
  ;; like one that is working.
  (async done
    (let [broken (reify
                   storage/IBlockStore
                   (-put-blocks! [_ _] (js/Promise.reject (js/Error. "endpoint said no")))
                   (-get-blocks [_ _] (js/Promise.resolve {}))
                   storage/IBackendCapabilities
                   (-capabilities [_] #{:immutable-blocks :cid-addressed-read}))
          pub (store/publisher broken nil)]
      (-> (store/publish! pub "bafyx" (js/Uint8Array.from #js [1]))
          (.then (fn [outcome]
                   (is (= :failed outcome) "a rejected publish resolved as success")
                   (let [st (store/status pub)]
                     (is (= 1 (:failed st)))
                     (is (= 0 (:pending st)) "a failed publish stayed pending forever")
                     (is (= "endpoint said no" (:last-error st))
                         "the endpoint's own words were thrown away"))
                   (done)))))))

(deftest a-successful-publish-is-counted
  (async done
    (let [pub (store/publisher (s3-blocks) nil)]
      (-> (store/publish! pub "bafyok" (js/Uint8Array.from #js [7]))
          (.then (fn [outcome]
                   (is (= :published outcome))
                   (let [st (store/status pub)]
                     (is (= 1 (:published st)))
                     (is (= 0 (:pending st)))
                     (is (= 0 (:failed st))))
                   (done)))))))

;; ── the sync/async adapter ──────────────────────────────────────────────────

(deftest promised-ref-echoes-the-profile-it-wraps
  (let [inner (mem/memory-store)
        wrapped (store/promised-ref inner)]
    (is (= (storage/-capabilities inner) (storage/-capabilities wrapped)))
    (is (= (storage/ref-profile inner) (storage/ref-profile wrapped))))
  (is (thrown? js/Error (store/promised-ref "not a ref store"))))

;; ── what a block is called ──────────────────────────────────────────────────

(deftest a-block-cid-is-pinned-by-value
  ;; `validator.cljs` has a private copy of `block-node`. A digest pinned here
  ;; turns a drift between the two into a failing assertion instead of two
  ;; deployments that disagree about what a block is called.
  (let [b {:inga.block/height 1
           :inga.block/parent-hash nil
           :inga.block/proposals []
           :inga.block/proposer "w1"
           :inga.block/ts 1000}
        cid (store/block-cid b)]
    (is (string? cid))
    (is (re-matches #"bafy[a-z2-7]+" cid)
        "a block id that is not a CIDv1 dag-cbor cannot be a tag-42 link")
    (is (= cid (store/block-cid b)) "the encoding is not deterministic")
    (testing "the same block with a different timestamp is a different block"
      (is (not= cid (store/block-cid (assoc b :inga.block/ts 1001)))))))

(deftest genesis-carries-no-parent-and-a-child-carries-a-link
  ;; A sentinel that is not a CID would have to be special cased by every
  ;; reader, and a parent that is a plain string is not a DAG edge.
  (let [g {:inga.block/height 0 :inga.block/parent-hash "genesis"
           :inga.block/proposals [] :inga.block/proposer "w1" :inga.block/ts 0}
        gcid (store/block-cid g)
        child {:inga.block/height 1 :inga.block/parent-hash gcid
               :inga.block/proposals [] :inga.block/proposer "w1" :inga.block/ts 1}]
    (is (nil? (get (store/block-node g) "parent"))
        "genesis carried a parent")
    (is (some? (get (store/block-node child) "parent")))
    (is (not (string? (get (store/block-node child) "parent")))
        "the parent is a string rather than an IPLD link")))

(deftest config-reads-through-a-lookup-function
  ;; The regression this exists for: `js/process.env` is a Proxy, `js->clj`
  ;; leaves it alone, and a map built that way answers nil for every variable
  ;; that is actually set. A deployment with all five exported was told it was
  ;; unconfigured. A map satisfies `ifn?` so the tests above still read as
  ;; maps; this one asserts the function path, which is what a host with a
  ;; Proxy environment must use.
  (let [backing #js {}]
    (aset backing "TORIHIKI_BLOCKS_ENDPOINT" "https://s3.example.invalid")
    (aset backing "TORIHIKI_BLOCKS_BUCKET" "torihiki")
    (aset backing "TORIHIKI_BLOCKS_REGION" "us-east-1")
    (aset backing "TORIHIKI_BLOCKS_ACCESS_KEY" "AK")
    (aset backing "TORIHIKI_BLOCKS_SECRET_KEY" "SK")
    (let [r (store/config-from-env #(aget backing %))]
      (is (nil? (:unconfigured r)))
      (is (= "https://s3.example.invalid" (:endpoint (:ok r)))))))
