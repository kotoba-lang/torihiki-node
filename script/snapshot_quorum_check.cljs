;; The check `/adopt` makes, run from outside: fetch every replica's newest
;; checkpoint, restore it HERE, compute its state root HERE, and see whether a
;; quorum agrees. A peer reporting its own root would be trusted about exactly
;; the thing in dispute.
(ns snapshot-quorum-check
  (:require [torihiki.snapshot :as tsnap] [torihiki.state :as st] [promesa.core :as p]))
(def base
  ;; v2 by default. These pointed at the FIRST deployment, which has been
  ;; stuck on `code-version 100` since deploys stopped reaching it — so a
  ;; failure here was a fact about a chain nobody can fix rather than about
  ;; the code under test. `TORIHIKI_BASE` overrides for the rare case where
  ;; the old chain IS the subject.
  (or (some-> js/process .-env .-TORIHIKI_BASE)
      "https://torihiki-validator-v2.04-feasts-minded.workers.dev"))
(defn GET [p*] (p/let [r (js/fetch (str base p*)) j (.json r)] (js->clj j :keywordize-keys true)))
(p/let [rs (p/all (for [w ["w1" "w2" "w3" "w4"]] (GET (str "/snapshot?w=" w "&h=1274600"))))
        scored (doall
                (keep (fn [d]
                        (when (:ok d)
                          (try
                            (let [snap (tsnap/read-string* (:edn d))
                                  ms (tsnap/restore (:machine-state snap))]
                              {:witness (:witness d) :height (:height d)
                               :bytes (count (:edn d)) :root (st/state-root ms)})
                            (catch :default e {:witness (:witness d) :error (str e)}))))
                      rs))]
  (doseq [s scored] (println " " (pr-str s)))
  (let [groups (group-by (juxt :height :root) (remove :error scored))
        [k g] (last (sort-by (comp count val) groups))]
    (println)
    (println "largest agreeing group:" (pr-str k) "->" (mapv :witness g))
    (println "quorum needed: 3, have:" (count g))
    (println (if (>= (count g) 3)
               "PASS — a quorum agrees on one state; /adopt would accept it"
               "BLOCKED — no quorum agrees; /adopt would refuse (409)"))))
