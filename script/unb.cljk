(ns unb (:require [torihiki.auth :as auth] [promesa.core :as p]))
(def base
  ;; v2 by default. These pointed at the FIRST deployment, which has been
  ;; stuck on `code-version 100` since deploys stopped reaching it — so a
  ;; failure here was a fact about a chain nobody can fix rather than about
  ;; the code under test. `TORIHIKI_BASE` overrides for the rare case where
  ;; the old chain IS the subject.
  (or (some-> js/process .-env .-TORIHIKI_BASE)
      "https://torihiki-validator-v2.04-feasts-minded.workers.dev"))
(def chain-id "torihiki-engi-devnet-1")
(def acct 32650676912972)
(defn GET [p*] (p/let [r (js/fetch (str base p*)) j (.json r)] (js->clj j :keywordize-keys true)))
(p/let [a (GET (str "/account?w=w1&id=" acct))]
  (println "next-nonce" (:next-nonce a) " bound-key" (subs (or (:bound-key a) "") 0 12))
  (println "payload for unbond:")
  (println (auth/signing-payload chain-id acct (:next-nonce a)
                                 {:tx :unbond :account acct :validator 4242 :amount 3000000})))
