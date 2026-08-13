;; A scale order against the DEPLOYED chain: one transaction, a ladder of
;; resting orders walking AWAY from the book.
(ns scale-e2e
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
(p/let [me (mk)
        _ (println "account" (:acct me))
        _ (POST "/faucet?w=w1" {:account (:acct me)})
        _ (wait 10000)
        n (p/let [a (GET (str "/account?w=w1&id=" (:acct me)))] (:next-nonce a))
        r (tx me n {:tx :scale :account (:acct me) :market 2
                    :side 0 :level 60 :qty 1 :count* 5 :step 2 :flags 0})
        _ (println "scale ->" (pr-str r))
        _ (wait 10000)
        o (GET (str "/orders?w=w1&m=2&account=" (:acct me)))
        mine (sort-by :level (:orders o))]
  (println "rungs:" (pr-str (mapv (juxt :level :qty) mine)))
  (println)
  (cond
    (empty? mine) (println "INCONCLUSIVE — nothing rested; the ladder never arrived")
    (not= 5 (count mine)) (println "FAIL — expected 5 rungs, got" (count mine))
    (not= [52 54 56 58 60] (mapv :level mine))
    (println "FAIL — the rungs are not where the ladder said:" (mapv :level mine))
    :else (println "PASS — one transaction placed a five-rung ladder walking away from the book")))
