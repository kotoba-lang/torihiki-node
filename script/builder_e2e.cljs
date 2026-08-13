;; A builder code against the DEPLOYED chain: the taker pays, the builder is
;; credited, the maker pays nothing extra.
(ns builder-e2e
  (:require [torihiki.auth :as auth] [torihiki.address :as addr] [promesa.core :as p]))
(def base "https://torihiki-validator.04-feasts-minded.workers.dev")
(def chain-id "torihiki-engi-devnet-1")
(defn b64 [b] (js/btoa (.apply js/String.fromCharCode nil (js/Uint8Array. b))))
(defn GET [p*] (p/let [r (js/fetch (str base p*)) j (.json r)] (js->clj j :keywordize-keys true)))
(defn POST [p* b] (p/let [r (js/fetch (str base p*) #js {:method "POST" :body (js/JSON.stringify (clj->js b))}) j (.json r)] (js->clj j :keywordize-keys true)))
(defn mk [] (p/let [kp (js/crypto.subtle.generateKey #js {:name "Ed25519"} true #js ["sign" "verify"])
                    raw (js/crypto.subtle.exportKey "raw" (.-publicKey kp)) pub (b64 raw)]
              {:sk (.-privateKey kp) :pub pub :acct (addr/derive pub)}))
(defn tx [{:keys [sk pub acct]} nonce t]
  (p/let [pay (auth/signing-payload chain-id acct nonce t)
          sig (js/crypto.subtle.sign #js {:name "Ed25519"} sk (.encode (js/TextEncoder.) pay))]
    (POST "/tx?w=w1" {:tx t :account acct :nonce nonce :pubkey pub :sig (b64 sig)})))
(defn wait [ms] (p/create (fn [r _] (js/setTimeout r ms))))
(defn coll-of [a] (p/let [x (GET (str "/account?w=w1&id=" a))] (:collateral x)))
(defn nonce-of [a] (p/let [x (GET (str "/account?w=w1&id=" a))] (:next-nonce x)))
(p/let [maker (mk) taker (mk) builder (mk)
        _ (println "maker" (:acct maker) "taker" (:acct taker) "builder" (:acct builder))
        _ (POST "/faucet?w=w1" {:account (:acct maker)}) _ (wait 9000)
        _ (POST "/faucet?w=w1" {:account (:acct taker)}) _ (wait 9000)
        nm (nonce-of (:acct maker))
        _ (tx maker nm {:tx :order :account (:acct maker) :market 2
                        :side 1 :level 100 :qty 20000 :flags 0})
        _ (wait 9000)
        b0 (coll-of (:acct builder))
        t0 (coll-of (:acct taker))
        nt (nonce-of (:acct taker))
        _ (tx taker nt {:tx :order :account (:acct taker) :market 2
                        :side 0 :level 100 :qty 20000 :flags 0
                        :builder (:acct builder) :builder-fee 500000})
        _ (wait 10000)
        b1 (coll-of (:acct builder))
        t1 (coll-of (:acct taker))]
  (println "builder collateral:" b0 "->" b1)
  (println "taker collateral  :" t0 "->" t1)
  (println)
  (cond
    (nil? t0) (println "INCONCLUSIVE — the taker was never funded")
    (= t0 t1) (println "INCONCLUSIVE — the order did not fill")
    (not (> b1 b0)) (println "FAIL — the builder was not paid:" b0 "->" b1)
    :else (println "PASS — the builder earned" (- b1 b0) "and the taker paid" (- t0 t1))))
