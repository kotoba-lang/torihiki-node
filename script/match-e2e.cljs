;; A second account sells into the resting bid. A book that accepts orders and
;; a book that MATCHES them are different claims, and only one of them had been
;; shown on the deployed chain.
(ns match-e2e
  (:require [torihiki.auth :as auth]
            [torihiki.address :as addr]
            [promesa.core :as p]))

(def base "https://torihiki-validator.04-feasts-minded.workers.dev")
(def chain-id "torihiki-engi-devnet-1")

(defn b64 [buf] (js/btoa (.apply js/String.fromCharCode nil (js/Uint8Array. buf))))

(defn GET [path]
  (p/let [r (js/fetch (str base path)) j (.json r)] (js->clj j :keywordize-keys true)))

(defn POST [path body]
  (p/let [r (js/fetch (str base path)
                      #js {:method "POST" :body (js/JSON.stringify (clj->js body))})
          j (.json r)]
    (js->clj j :keywordize-keys true)))

(defn send-tx [sk pub account nonce tx]
  (p/let [payload (auth/signing-payload chain-id account nonce tx)
          sig (js/crypto.subtle.sign #js {:name "Ed25519"} sk
                                     (.encode (js/TextEncoder.) payload))]
    (POST "/tx?w=w2" {:tx tx :account account :nonce nonce :pubkey pub :sig (b64 sig)})))

(defn wait [ms] (p/create (fn [res _] (js/setTimeout res ms))))

(p/let [kp  (js/crypto.subtle.generateKey #js {:name "Ed25519"} true #js ["sign" "verify"])
        raw (js/crypto.subtle.exportKey "raw" (.-publicKey kp))
        pub (b64 raw)
        sk  (.-privateKey kp)
        acct (addr/derive pub)
        before (GET "/book?w=w1")
        _ (println "book before:" (pr-str (:bids before)) "asks" (pr-str (:asks before)))
        _ (println "seller account" acct)
        d (send-tx sk pub acct 1 {:tx :deposit :account acct :amount 1000000000})
        _ (println "deposit ->" (pr-str d))
        _ (wait 5000)
        ;; side 1 = sell, into the resting bid at level 100
        o (send-tx sk pub acct 2 {:tx :order :account acct :market 1
                                  :side 1 :level 100 :qty 60 :flags 0})
        _ (println "sell    ->" (pr-str o))
        _ (wait 7000)
        books (p/all (for [w ["w1" "w2" "w3" "w4"]]
                       (p/let [b (GET (str "/book?w=" w))] [w b])))
        trades (p/all (for [w ["w1" "w2" "w3" "w4"]]
                        (p/let [t (GET (str "/trades?w=" w))] [w t])))]
  (println)
  (doseq [[w b] books] (println w "bids" (pr-str (:bids b)) "asks" (pr-str (:asks b))))
  (println)
  (doseq [[w t] trades] (println w "trades" (pr-str (:trades t)))))
