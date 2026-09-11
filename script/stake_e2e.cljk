;; Bonding against the DEPLOYED chain: the bond locks collateral without
;; moving it, and shows up as the validator's weight.
(ns stake-e2e
  (:require [torihiki.auth :as auth] [torihiki.address :as addr] [promesa.core :as p]))
(def base
  ;; v2 by default. These pointed at the FIRST deployment, which has been
  ;; stuck on `code-version 100` since deploys stopped reaching it — so a
  ;; failure here was a fact about a chain nobody can fix rather than about
  ;; the code under test. `TORIHIKI_BASE` overrides for the rare case where
  ;; the old chain IS the subject.
  (or (some-> js/process .-env .-TORIHIKI_BASE)
      "https://torihiki-validator-v2.04-feasts-minded.workers.dev"))
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
(p/let [me (mk) validator 4242
        _ (println "delegator" (:acct me))
        _ (POST "/faucet?w=w1" {:account (:acct me)}) _ (wait 10000)
        a0 (acct-of (:acct me))
        n (:next-nonce a0)
        _ (tx me n {:tx :bond :account (:acct me) :validator validator :amount 3000000})
        _ (wait 10000)
        a1 (acct-of (:acct me))
        s1 (GET (str "/stake?w=w1&account=" (:acct me)))
        _ (println "collateral:" (:collateral a0) "->" (:collateral a1))
        _ (println "free:" (:free-collateral a0) "->" (:free-collateral a1))
        _ (println "bonded:" (:total-bonded s1) " unbonding:" (count (:unbonding s1)))
        ;; BOUND, not inlined. `acct-of` returns a promise, and `:next-nonce`
        ;; on a promise is nil — which the node reports as
        ;; `malformed-envelope`, i.e. exactly what it is. The first version
        ;; inlined it and read the refusal as the feature not working.
        a2 (acct-of (:acct me))
        n2 (:next-nonce a2)
        ub (tx me n2 {:tx :unbond :account (:acct me) :validator validator :amount 3000000})
        _ (println "unbond tx ->" (pr-str ub) " nonce" n2)
        _ (wait 14000)
        s2 (GET (str "/stake?w=w1&account=" (:acct me)))]
  (println "after unbond -> bonded:" (:total-bonded s2) " unbonding:" (:unbonding s2))
  (println)
  (cond
    (nil? (:collateral a1)) (println "INCONCLUSIVE — the account was never funded")
    (not= (:collateral a0) (:collateral a1))
    (println "FAIL — the bond MOVED the collateral; slashing would have nothing to take")
    (not= 3000000 (:total-bonded s1)) (println "FAIL — the bond did not register:" (:total-bonded s1))
    (not= (- (:free-collateral a0) 3000000) (:free-collateral a1))
    (println "FAIL — bonded collateral was still free:" (:free-collateral a1))
    (pos? (:total-bonded s2)) (println "FAIL — unbonding left the stake counted as weight")
    (empty? (:unbonding s2)) (println "FAIL — the unbonding was released immediately")
    :else (println "PASS — the bond locked collateral in place, and unbonding queued it")))
