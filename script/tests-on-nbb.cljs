;; This repository's unit tests, on nbb.
;;
;;   KOTOBA_CHECKOUTS=<dir-of-sibling-checkouts> \
;;     nbb --classpath "$(nbb script/nbb-classpath.cljs)" script/tests-on-nbb.cljs
;;
;; ## Why this is the first one
;;
;; Until now this repository had no test directory. What it has is a shelf of
;; `script/*_e2e.cljs` that talk to a deployed endpoint over HTTP -- valuable,
;; and unable to say anything at all while the devnet is stalled, which it has
;; been since 2026-08-15. A validator with no test that runs offline is a
;; validator whose persistence layer can only be checked by deploying it.
;;
;; The floor below refuses rather than reporting a pass on a short classpath,
;; for the same reason `torihiki`'s runner does: a suite that could not load
;; its namespaces and one that loaded them and found nothing print the same
;; two numbers otherwise.
(ns tests-on-nbb
  (:require [cljs.test :as ct]
            [torihiki-node.store-test]))

(def ^:private expected-namespaces 1)
(defonce ^:private ns-seen (atom 0))

(defmethod ct/report [::ct/default :begin-test-ns] [_] (swap! ns-seen inc))

(defmethod ct/report [::ct/default :end-run-tests] [m]
  (let [{:keys [test pass fail error]} m
        ran-ns @ns-seen]
    (println (str "namespaces " ran-ns "/" expected-namespaces))
    (println (str "Ran " test " tests containing " (+ pass fail error) " assertions."))
    (println (str fail " failures, " error " errors."))
    (cond
      (or (zero? test) (< ran-ns expected-namespaces))
      (do (println (str "REFUSING to report a pass: " ran-ns " of " expected-namespaces
                        " namespaces ran and " test " tests executed."))
          (js/process.exit 2))

      (pos? (+ fail error)) (js/process.exit 1)
      :else (println "TESTS-ON-NBB: pass"))))

(ct/run-tests 'torihiki-node.store-test)
