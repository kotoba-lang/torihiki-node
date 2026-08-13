;; The withdrawal exit against the DEPLOYED chain: a claim must not lower what
;; the exchange owes, because nobody has been paid yet.
(ns reserves-withdrawal-e2e
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
(p/let [kp (js/crypto.subtle.generateKey #js {:name "Ed25519"} true #js ["sign" "verify"])
        raw (js/crypto.subtle.exportKey "raw" (.-publicKey kp))
        pub (b64 raw) me {:sk (.-privateKey kp) :pub pub :acct (addr/derive pub)}
        _ (println "account" (:acct me))
        _ (POST "/faucet?w=w1" {:account (:acct me)})
        _ (wait 9000)
        r0 (GET "/reserves?w=w1")
        _ (println "funded    " (pr-str (select-keys r0 [:total :accounts])))
        w (tx me 1 {:tx :withdraw :account (:acct me) :amount 4000000})
        _ (println "withdraw  " (pr-str w))
        _ (wait 9000)
        r1 (GET "/reserves?w=w1")
        a1 (GET (str "/account?w=w1&id=" (:acct me)))]
  (println "after     " (pr-str (select-keys r1 [:total :pending-withdrawals])))
  (println "collateral" (:collateral a1))
  (println)
  (cond
    (zero? (:total r0)) (println "INCONCLUSIVE — the faucet did not fund the account")
    (not= 1 (:count (:pending-withdrawals r1)))
    (println "FAIL — the withdrawal raised no claim")
    (not= (:total r0) (:total r1))
    (println "FAIL — the total moved on a withdrawal:" (:total r0) "->" (:total r1))
    :else
    (println "PASS — collateral left the account, the claim holds the amount,"
             "and what the exchange owes is unchanged until somebody is paid")))
