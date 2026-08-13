;; A vault against the DEPLOYED chain: outside money in, shares out, and a
;; withdrawal that pays a share of what the vault holds.
(ns vault-e2e
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
(def vault 900)
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
(defn nonce-of [a] (p/let [x (GET (str "/account?w=w1&id=" a))] (:next-nonce x)))
(defn coll-of [a] (p/let [x (GET (str "/account?w=w1&id=" a))] (:collateral x)))
(p/let [me (mk)
        _ (println "depositor" (:acct me))
        _ (POST "/faucet?w=w1" {:account (:acct me)}) _ (wait 10000)
        v0 (GET (str "/vault?w=w1&id=" vault))
        c0 (coll-of (:acct me))
        n (nonce-of (:acct me))
        _ (tx me n {:tx :vault-deposit :account (:acct me) :vault vault :amount 4000000})
        _ (wait 10000)
        v1 (GET (str "/vault?w=w1&id=" vault))
        c1 (coll-of (:acct me))
        mine (->> (:holders v1) (filter #(= (:acct me) (:account %))) first :shares)
        _ (println "vault collateral:" (:collateral v0) "->" (:collateral v1))
        _ (println "my shares:" mine "  my collateral:" c0 "->" c1)
        n2 (nonce-of (:acct me))
        _ (tx me n2 {:tx :vault-withdraw :account (:acct me) :vault vault :shares (quot (or mine 0) 2)})
        _ (wait 10000)
        v2 (GET (str "/vault?w=w1&id=" vault))
        c2 (coll-of (:acct me))]
  (println "after withdrawing half:" (:collateral v1) "->" (:collateral v2) " my collateral" c1 "->" c2)
  (println)
  (cond
    (nil? mine) (println "INCONCLUSIVE — the deposit never landed")
    (not (pos? mine)) (println "FAIL — no shares were minted")
    (not= (- c0 4000000) c1) (println "FAIL — the depositor's collateral did not move by the deposit")
    (not (> c2 c1)) (println "FAIL — the withdrawal paid nothing")
    :else (println "PASS — deposited for" mine "shares and withdrew half for" (- c2 c1))))
