;; Two real accounts against the DEPLOYED chain: one rests an order, the other
;; tries to cancel it. Signed here with real Ed25519; the payload comes from
;; torihiki.auth so there is exactly one definition of the signed bytes.
(ns cancel-auth-e2e
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
(def level 50)   ; well below the mark, so it rests instead of crossing

(defn b64 [buf] (js/btoa (.apply js/String.fromCharCode nil (js/Uint8Array. buf))))
(defn GET [path] (p/let [r (js/fetch (str base path)) j (.json r)] (js->clj j :keywordize-keys true)))
(defn POST [path body]
  (p/let [r (js/fetch (str base path) #js {:method "POST" :body (js/JSON.stringify (clj->js body))})
          j (.json r)] (js->clj j :keywordize-keys true)))
(defn send-tx [{:keys [sk pub acct]} nonce tx]
  (p/let [payload (auth/signing-payload chain-id acct nonce tx)
          sig (js/crypto.subtle.sign #js {:name "Ed25519"} sk (.encode (js/TextEncoder.) payload))]
    (POST "/tx?w=w1" {:tx tx :account acct :nonce nonce :pubkey pub :sig (b64 sig)})))
(defn wait [ms] (p/create (fn [res _] (js/setTimeout res ms))))
(defn mk []
  (p/let [kp (js/crypto.subtle.generateKey #js {:name "Ed25519"} true #js ["sign" "verify"])
          raw (js/crypto.subtle.exportKey "raw" (.-publicKey kp))
          pub (b64 raw)]
    {:sk (.-privateKey kp) :pub pub :acct (addr/derive pub)}))
(defn mine [orders acct]
  (->> (:orders orders) (filter #(= acct (:owner %))) (map :oid) first))

(p/let [victim (mk) attacker (mk)
        _ (println "victim  " (:acct victim))
        _ (println "attacker" (:acct attacker))
        _ (send-tx victim 1 {:tx :deposit :account (:acct victim) :amount 1000000000})
        _ (send-tx attacker 1 {:tx :deposit :account (:acct attacker) :amount 1000000000})
        _ (wait 6000)
        o (send-tx victim 2 {:tx :order :account (:acct victim) :market 1
                             :side 0 :level level :qty 7 :flags 0})
        _ (println "victim order ->" (pr-str o))
        _ (wait 7000)
        b (GET (str "/orders?w=w1&account=" (:acct victim)))
        oid (mine b (:acct victim))
        _ (println "victim oid  ->" oid)
        c (send-tx attacker 2 {:tx :cancel :account (:acct attacker) :market 1 :oid oid})
        _ (println "attacker cancel ->" (pr-str c))
        _ (wait 7000)
        b2 (GET (str "/orders?w=w1&account=" (:acct victim)))
        still (mine b2 (:acct victim))
        ;; The converse. Without it, "still resting" is also what a completely
        ;; broken cancel looks like, and the test would pass for the wrong
        ;; reason.
        own (send-tx victim 3 {:tx :cancel :account (:acct victim) :market 1 :oid oid})
        _ (println "owner cancel    ->" (pr-str own))
        _ (wait 7000)
        b3 (GET (str "/orders?w=w1&account=" (:acct victim)))
        after-own (mine b3 (:acct victim))]
  (println)
  (println "after stranger cancel:" still)
  (println "after owner cancel   :" after-own)
  (cond
    ;; Guard first. A run where the order never rested has nil everywhere, and
    ;; every comparison below is then trivially satisfied — the earlier version
    ;; of this script reported PASS on exactly that, which is a test that
    ;; cannot fail and therefore says nothing.
    (nil? oid) (println "INCONCLUSIVE — the victim never got a resting order; nothing was tested")
    (not= oid still) (println "FAIL — a stranger removed it. was" oid "now" still)
    (some? after-own) (println "FAIL — the owner could not cancel either; the test proved nothing")
    :else (println "PASS — the stranger could not cancel it and the owner could")))
