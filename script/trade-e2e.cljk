;; A real order, signed in this process, against the DEPLOYED chain — then the
;; same question asked of all four replicas.
;;
;; The payload comes from torihiki.auth, not from here. A second implementation
;; of the signed bytes is the one thing that must not exist: when it drifts the
;; chain says `bad-signature`, which reads as a crypto problem and is not one.
(ns trade-e2e
  (:require [torihiki.auth :as auth]
            [torihiki.address :as addr]
            [promesa.core :as p]))

(def base
  ;; v2 by default. These pointed at the FIRST deployment, which has been
  ;; stuck on `code-version 100` since deploys stopped reaching it — so a
  ;; failure here was a fact about a chain nobody can fix rather than about
  ;; the code under test. `TORIHIKI_BASE` overrides for the rare case where
  ;; the old chain IS the subject.
  (or (some-> js/process .-env .-TORIHIKI_BASE)
      "https://torihiki-validator-v2.04-feasts-minded.workers.dev"))
(def chain-id "torihiki-engi-devnet-1")

(defn b64 [buf] (js/btoa (.apply js/String.fromCharCode nil (js/Uint8Array. buf))))

(defn GET [path]
  (p/let [r (js/fetch (str base path)) j (.json r)]
    (js->clj j :keywordize-keys true)))

(defn POST [path body]
  (p/let [r (js/fetch (str base path)
                      #js {:method "POST" :body (js/JSON.stringify (clj->js body))})
          j (.json r)]
    (js->clj j :keywordize-keys true)))

(defn send-tx [sk pub account nonce tx]
  (p/let [payload (auth/signing-payload chain-id account nonce tx)
          sig (js/crypto.subtle.sign #js {:name "Ed25519"} sk
                                     (.encode (js/TextEncoder.) payload))]
    (POST "/tx?w=w1" {:tx tx :account account :nonce nonce
                      :pubkey pub :sig (b64 sig)})))

(defn wait [ms] (p/create (fn [res _] (js/setTimeout res ms))))

(p/let [kp  (js/crypto.subtle.generateKey #js {:name "Ed25519"} true #js ["sign" "verify"])
        raw (js/crypto.subtle.exportKey "raw" (.-publicKey kp))
        pub (b64 raw)
        sk  (.-privateKey kp)
        acct (addr/derive pub)
        _ (println "account" acct "from key" (str (subs pub 0 16) "..."))
        d (send-tx sk pub acct 1 {:tx :deposit :account acct :amount 1000000000})
        _ (println "deposit ->" (pr-str d))
        _ (wait 5000)
        o (send-tx sk pub acct 2 {:tx :order :account acct :market 1
                                  :side 0 :level 100 :qty 100 :flags 0})
        _ (println "order   ->" (pr-str o))
        _ (wait 7000)
        accts (p/all (for [w ["w1" "w2" "w3" "w4"]]
                       (p/let [a (GET (str "/account?w=" w "&id=" acct))] [w a])))
        books (p/all (for [w ["w1" "w2" "w3" "w4"]]
                       (p/let [b (GET (str "/book?w=" w))] [w b])))]
  (println)
  (doseq [[w a] accts]
    (println w "collateral" (:collateral a) "next-nonce" (:next-nonce a)
             "bound-key" (some? (:bound-key a))))
  (println)
  (doseq [[w b] books]
    (println w "bids" (pr-str (:bids b)) "resting" (:resting b))))
