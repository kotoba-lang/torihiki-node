;; An agent wallet against the DEPLOYED chain: it must trade and must not be
;; able to take the money.
(ns agent-wallet-e2e
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
              {:sk (.-privateKey kp) :pub pub}))
(defn send-as [signer acct nonce t]
  (p/let [pay (auth/signing-payload chain-id acct nonce t)
          sig (js/crypto.subtle.sign #js {:name "Ed25519"} (:sk signer) (.encode (js/TextEncoder.) pay))]
    (POST "/tx?w=w1" {:tx t :account acct :nonce nonce :pubkey (:pub signer) :sig (b64 sig)})))
(defn wait [ms] (p/create (fn [r _] (js/setTimeout r ms))))
(defn nonce-of [acct] (p/let [a (GET (str "/account?w=w1&id=" acct))] (:next-nonce a)))
(p/let [owner (mk) ag (mk)
        acct (addr/derive (:pub owner))
        _ (println "account" acct)
        _ (POST "/faucet?w=w1" {:account acct})
        _ (wait 9000)
        n1 (nonce-of acct)
        _ (send-as owner acct n1 {:tx :authorize-agent :account acct :agent (:pub ag) :expires nil})
        _ (wait 9000)
        ags (GET (str "/agents?w=w1&account=" acct))
        _ (println "agents    " (pr-str (:agents ags)))
        _ (println "may-not   " (pr-str (:may-not ags)))
        ;; the agent trades
        n2 (nonce-of acct)
        o (send-as ag acct n2 {:tx :order :account acct :market 1 :side 0 :level 55 :qty 2 :flags 0})
        _ (println "agent order" (pr-str o))
        _ (wait 9000)
        orders (GET (str "/orders?w=w1&account=" acct))
        ;; the agent tries to take the money
        n3 (nonce-of acct)
        bal-before (GET (str "/account?w=w1&id=" acct))
        w (send-as ag acct n3 {:tx :withdraw :account acct :amount 5000000})
        _ (println "agent withdraw" (pr-str w))
        _ (wait 9000)
        bal-after (GET (str "/account?w=w1&id=" acct))
        res (GET "/reserves?w=w1")]
  (println "collateral before/after:" (:collateral bal-before) "/" (:collateral bal-after))
  (println "pending claims:" (:count (:pending-withdrawals res)))
  (println)
  (cond
    (empty? (:agents ags)) (println "INCONCLUSIVE — the agent was never authorised")
    (empty? (:orders orders)) (println "FAIL — the agent could not trade; the wallet is useless")
    (not= (:collateral bal-before) (:collateral bal-after))
    (println "FAIL — the agent moved money:" (:collateral bal-before) "->" (:collateral bal-after))
    :else (println "PASS — the agent traded and could not withdraw")))
