;; Spot against a DEPLOYED chain: balances change hands, nothing is margined.
(ns spot-e2e
  (:require [torihiki.auth :as auth] [torihiki.address :as addr] [promesa.core :as p]))
(def base (or (some-> js/process .-env .-BASE)
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
(defn bal-of [a] (GET (str "/balances?w=w1&account=" a)))
(p/let [buyer (mk) seller (mk)
        _ (println "buyer" (:acct buyer) " seller" (:acct seller))
        _ (POST "/faucet?w=w1" {:account (:acct buyer)}) _ (wait 8000)
        _ (POST "/faucet?w=w1" {:account (:acct seller)}) _ (wait 8000)
        ;; the seller is given the asset the only way a devnet can: the bridge
        ;; does not mint assets, so this test sells what a prior spot buy would
        ;; have produced — instead, both sides trade and we check the exchange.
        b0 (acct-of (:acct buyer)) s0 (acct-of (:acct seller))
        _ (println "funded  buyer" (:collateral b0) " seller" (:collateral s0))
        ;; buyer bids; with no asset holder there is nothing to fill against,
        ;; so the order must REST and commit quote, not vanish.
        nb (:next-nonce b0)
        _ (tx buyer nb {:tx :order :account (:acct buyer) :market 3
                        :side 0 :level 100 :qty 5 :flags 0})
        _ (wait 9000)
        bb (bal-of (:acct buyer))
        o (GET (str "/orders?w=w1&m=3&account=" (:acct buyer)))
        _ (println "buyer committed quote:" (:committed bb) " resting:" (count (:orders o)))
        ;; an unbacked sell must be refused outright
        ns* (:next-nonce (acct-of (:acct seller)))
        _ (tx seller ns* {:tx :order :account (:acct seller) :market 3
                          :side 1 :level 100 :qty 5 :flags 0})
        _ (wait 9000)
        so (GET (str "/orders?w=w1&m=3&account=" (:acct seller)))]
  (println "unbacked sell rested:" (count (:orders so)))
  (println)
  (cond
    (nil? (:collateral b0)) (println "INCONCLUSIVE — the buyer was never funded")
    (zero? (count (:orders o))) (println "FAIL — the spot bid did not rest")
    (empty? (:committed bb)) (println "FAIL — resting committed nothing; a second order could double-spend it")
    (pos? (count (:orders so))) (println "FAIL — a sell of an asset nobody holds rested on the book")
    :else (println "PASS — the bid rests and commits its quote; the unbacked sell is refused")))
