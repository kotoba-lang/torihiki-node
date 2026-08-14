;; What a checkpoint carries, and what comes back out of it.
;;
;;   npx nbb -cp "src:$(clojure -Spath)" script/views_check.cljs
;;
;; ## Why a script and not a test namespace
;;
;; `validator.cljs` cannot be loaded outside a Worker — `script/loads.cljs`
;; says so and skips it, and it is true: under nbb it dies in `deftype`. So the
;; shape decisions live in `torihiki-node.views`, which is pure, and this runs
;; them. It is the only part of the checkpoint path that can be checked without
;; deploying, and it is the part where deploying to find out is worst: a wrong
;; shape is invisible until a replica restarts, and a replica restarts when the
;; venue is already short of a quorum.
;;
;; The EDN round trip is the real one — `torihiki.snapshot/write-string` and
;; `read-string*` are what the storage write and the restore actually use, and
;; going through them is what turns a map into a vector of pairs.
(ns views-check
  (:require [torihiki-node.views :as vw]
            [torihiki.snapshot :as tsnap]))

(def ^:const failures (atom 0))

(defn check [name ok?]
  (if ok?
    (println "  ok  " name)
    (do (swap! failures inc)
        (println "  FAIL" name))))

(defn round-trip
  "Through storage and back, the way `checkpoint!` and `restoreCheckpoint` do."
  [views]
  (tsnap/read-string* (tsnap/write-string views)))

;; A machine's candle index: two markets, more candles each than a checkpoint
;; carries.
(def machine-candles
  {1 (vec (for [i (range 4000)] {:o 500 :hi 505 :lo 495 :c 502 :v 9 :h i}))
   2 (vec (for [i (range 4000)] {:o 20 :hi 21 :lo 19 :c 20 :v 4 :h i}))})

(println "views_check")

(println "\n1. the bound is per market, and it is applied")
(let [cut (vw/checkpoint-candles 600 machine-candles)]
  (check "the shape written is a map keyed by market" (map? cut))
  (check "both markets survive" (= #{1 2} (set (keys cut))))
  (check "each market is cut to the bound"
         (= [600 600] [(count (get cut 1)) (count (get cut 2))]))
  (check "and it is the NEWEST 600, not the oldest"
         (= [3400 3999] [(:h (first (get cut 1))) (:h (peek (get cut 1)))]))
  (check "1200 candles written where the old bound wrote 8000"
         (= 1200 (reduce + (map count (vals cut))))))

(println "\n2. the shape survives storage")
(let [cut (vw/checkpoint-candles 600 machine-candles)
      back (round-trip {:tape [] :candles cut :refused []})
      ms (vw/merge-views {:books {} :candles {}} back)]
  (check "candles come back as a map" (map? (:candles ms)))
  (check "keyed by the same market ids" (= #{1 2} (set (keys (:candles ms)))))
  (check "with the same candles" (= cut (:candles ms)))
  (check "and the state beside them is untouched" (= {} (:books ms))))

(println "\n3. what the OLD checkpoint wrote is still readable")
;; `(vec (take-last 600 <a map>))` — 600 whole markets, written as pairs. Every
;; `views:latest` key on the deployed chain has this shape right now.
(let [old-shape (vec (take-last 600 machine-candles))
      back (round-trip {:candles old-shape})
      ms (vw/merge-views {} back)]
  (check "the old write really was a vector of pairs, not of candles"
         (and (vector? old-shape) (= 2 (count old-shape))
              (every? sequential? back)))
  (check "it is read back as a map" (map? (:candles ms)))
  (check "with both markets, not one" (= #{1 2} (set (keys (:candles ms)))))
  (check "and market 1 gets candles rather than a list of pairs"
         (every? map? (get (:candles ms) 1)))
  (check "market 2's candles are no longer lost"
         (= 4000 (count (get (:candles ms) 2)))))

(println "\n4. a checkpoint from before candles were per market")
(let [ancient (vec (for [i (range 10)] {:o 1 :c 2 :h i}))
      ms (vw/merge-views {} (round-trip {:candles ancient}))]
  (check "becomes the default market's" (= {vw/default-market ancient}
                                           (:candles ms))))

(println "\n5. the split: the state write carries no history")
(let [ms {:books {1 :a-book} :rejected [{:reason :unknown-order}]
          :tape [{:m 1 :h 3}] :candles machine-candles
          :refused [:insufficient-margin]}
      state (vw/state-only ms)]
  (doseq [k vw/view-keys]
    (check (str "the state write dropped " k) (not (contains? state k))))
  (check ":rejected is NOT dropped — it is under the state root"
         (= (:rejected ms) (:rejected state)))
  (check ":books is not dropped either" (= (:books ms) (:books state)))
  (check "and the write got smaller by the history it was carrying"
         (< (count (tsnap/write-string state))
            (quot (count (tsnap/write-string ms)) 100))))

(println "\n6. the refusal list is bounded")
(let [block (fn [rs n] (vw/absorb-refusals 2000 rs (repeat n {:reason :bad-nonce})))
      ;; A thousand blocks each refusing five things. Unbounded this is 5000
      ;; entries in the machine state and rising; it never fell.
      grown (reduce (fn [rs _] (block rs 5)) [] (range 1000))]
  (check "it stops at the bound" (= 2000 (count grown)))
  (check "the reasons are what is kept, not the rejections"
         (= :bad-nonce (first grown)))
  (check "an empty block adds nothing" (= grown (block grown 0)))
  (check "it keeps the RECENT ones"
         (= [:newest] (take-last 1 (vw/absorb-refusals 3 [:a :b :c]
                                                       [{:reason :newest}]))))
  (check "nil starts a list rather than throwing"
         (= [:bad-nonce] (vw/absorb-refusals 10 nil [{:reason :bad-nonce}]))))

(println "\n7. views that are absent do not erase what is there")
(let [ms {:tape [:a] :candles {1 [{:h 1}]} :refused [:x]}]
  (check "nil views leave the machine alone" (= ms (vw/merge-views ms nil)))
  (check "empty views leave the machine alone" (= ms (vw/merge-views ms {})))
  (check "and a views write with only a tape keeps the candles"
         (= (:candles ms) (:candles (vw/merge-views ms {:tape [:b]})))))

(println "\n8. merge-views takes history and nothing else")
;; The value read back from `views:latest` also carries `:height`, and a peer's
;; snapshot could carry anything at all. State comes from the snapshot.
(let [ms (vw/merge-views {:books {1 :mine}} {:tape [:t] :height 900
                                             :books {1 :theirs}})]
  (check "the height is not merged in" (not (contains? ms :height)))
  (check "and the books are this replica's" (= {1 :mine} (:books ms))))

(println)
(if (zero? @failures)
  (println "VIEWS-CHECK: pass")
  (do (println "VIEWS-CHECK:" @failures "failed")
      (set! (.-exitCode js/process) 1)))
