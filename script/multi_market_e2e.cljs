;; Two markets on the DEPLOYED chain: trade on the newly listed one, and check
;; the other is untouched. A market that exists and cannot be traded is a row
;; in a table, not a market.
(ns multi-market-e2e
  (:require [torihiki.auth :as auth] [torihiki.address :as addr] [promesa.core :as p]))
(def base "https://torihiki-validator.04-feasts-minded.workers.dev")
(def chain-id "torihiki-engi-devnet-1")
(defn b64 [b] (js/btoa (.apply js/String.fromCharCode nil (js/Uint8Array. b))))
(defn GET [p*] (p/let [r (js/fetch (str base p*)) j (.json r)] (js->clj j :keywordize-keys true)))
(defn POST [p* b] (p/let [r (js/fetch (str base p*) #js {:method "POST" :body (js/JSON.stringify (clj->js b))}) j (.json r)] (js->clj j :keywordize-keys true)))
(defn tx [{:keys [sk pub acct]} nonce t]
  (p/let [pay (auth/signing-payload chain-id acct nonce t)
          sig (js/crypto.subtle.sign #js {:name "Ed25519"} sk (.encode (js/TextEncoder.) pay))]
    (POST "/tx?w=w1" {:tx t :account acct :nonce nonce :pubkey pub :sig (b64 sig)})))
(defn wait [ms] (p/create (fn [r _] (js/setTimeout r ms))))
(defn mine [o a] (filter #(= a (:owner %)) (:orders o)))
(p/let [kp (js/crypto.subtle.generateKey #js {:name "Ed25519"} true #js ["sign" "verify"])
        raw (js/crypto.subtle.exportKey "raw" (.-publicKey kp))
        pub (b64 raw) me {:sk (.-privateKey kp) :pub pub :acct (addr/derive pub)}
        _ (println "account" (:acct me))
        _ (POST "/faucet?w=w1" {:account (:acct me)})
        _ (wait 9000)
        m1-before (GET "/orders?w=w1&m=1")
        _ (tx me 1 {:tx :order :account (:acct me) :market 2 :side 0 :level 60 :qty 3 :flags 0})
        _ (wait 9000)
        m2 (GET "/orders?w=w1&m=2")
        m1-after (GET "/orders?w=w1&m=1")
        mk2 (GET "/market?w=w1&m=2")
        mk1 (GET "/market?w=w1&m=1")
        ;; every replica must see the same thing on the new market
        all (p/all (for [w ["w1" "w2" "w3" "w4"]] (GET (str "/orders?w=" w "&m=2"))))]
  (println "market 2 orders (mine):" (pr-str (mapv #(select-keys % [:oid :level :qty]) (mine m2 (:acct me)))))
  (println "market 2 taker-fee:" (:taker-fee-rate mk2) " market 1 taker-fee:" (:taker-fee-rate mk1))
  (println "market 1 order count before/after:" (count (:orders m1-before)) "/" (count (:orders m1-after)))
  (println "per-replica counts on market 2:" (pr-str (mapv #(count (:orders %)) all)))
  (println)
  (cond
    (empty? (mine m2 (:acct me))) (println "FAIL — the order did not rest on market 2")
    (not= (count (:orders m1-before)) (count (:orders m1-after)))
    (println "FAIL — trading market 2 changed market 1")
    (= (:taker-fee-rate mk1) (:taker-fee-rate mk2))
    (println "INCONCLUSIVE — both markets have identical parameters; nothing distinguishes them")
    (not (apply = (map #(count (:orders %)) all)))
    (println "FAIL — replicas disagree about market 2")
    :else (println "PASS — market 2 trades, has its own fees, and every replica agrees;"
                   "market 1 is untouched")))
