;; Every feature written after the first deployment froze, run against the
;; chain that can actually take new code.
(ns v2-features-e2e
  (:require [torihiki.auth :as auth] [torihiki.address :as addr] [promesa.core :as p]))
(def base "https://torihiki-validator-v2.04-feasts-minded.workers.dev")
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
(defn acct-of [a] (GET (str "/account?w=w1&id=" a)))
(p/let [me (mk)
        _ (println "account" (:acct me))
        _ (POST "/faucet?w=w1" {:account (:acct me)}) _ (wait 9000)
        a0 (acct-of (:acct me))
        free0 (:free-collateral a0)

        ;; 1. chosen leverage — a stricter margin requirement than the market's
        _ (tx me (:next-nonce a0) {:tx :set-leverage :account (:acct me) :market 1 :leverage 2})
        _ (wait 9000)
        a1 (acct-of (:acct me))

        ;; 2. sub-account — a second margin pool, and a transfer into it
        sub (+ 880000000000 (mod (:acct me) 1000000))
        _ (tx me (:next-nonce a1) {:tx :create-sub-account :account (:acct me) :sub sub})
        _ (wait 9000)
        a2 (acct-of (:acct me))
        _ (tx me (:next-nonce a2) {:tx :transfer :account (:acct me) :to sub :amount 3000000})
        _ (wait 9000)
        subacct (acct-of sub)
        mine (acct-of (:acct me))

        ;; 3. reserve attestation — the bridge says what the escrow holds
        r0 (GET "/reserves?w=w1")]
  (println "leverage set ->" (:chosen-leverage a1) "(reported by /account, may be absent)")
  (println "sub-account collateral:" (:collateral subacct) " mine:" (:collateral mine))
  (println "reserves:" (pr-str (select-keys r0 [:total :attested :shortfall])))
  (println)
  (cond
    (nil? free0) (println "INCONCLUSIVE — the account was never funded")
    (not= 3000000 (:collateral subacct))
    (println "FAIL — the transfer into the sub-account did not land:" (:collateral subacct))
    (not (contains? r0 :shortfall))
    (println "FAIL — /reserves does not report a shortfall field")
    (not (nil? (:shortfall r0)))
    (println "FAIL — a shortfall was reported with nobody having attested")
    :else (println "PASS — sub-account funded, /reserves reports the attestation shape,"
                   "and silence is nil rather than zero")))
