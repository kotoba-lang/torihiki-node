;; A TWAP against the DEPLOYED chain: it must fill over blocks, not at once.
(ns twap-e2e
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
(defn nonce-of [a] (p/let [x (GET (str "/account?w=w1&id=" a))] (:next-nonce x)))
(defn pos-of [a] (p/let [x (GET (str "/account?w=w1&id=" a))]
                   (get-in x [:positions (keyword "2") :size]
                           (get-in x [:positions :2 :size] 0))))
(p/let [seller (mk) buyer (mk)
        _ (println "seller" (:acct seller) " buyer" (:acct buyer))
        _ (POST "/faucet?w=w1" {:account (:acct seller)})
        _ (wait 10000)
        _ (POST "/faucet?w=w1" {:account (:acct buyer)})
        _ (wait 10000)
        ns* (nonce-of (:acct seller))
        ;; a wall of asks on market 2 for the TWAP to eat
        _ (tx seller ns* {:tx :order :account (:acct seller) :market 2
                          :side 1 :level 100 :qty 400 :flags 0})
        _ (wait 9000)
        nb (nonce-of (:acct buyer))
        _ (tx buyer nb {:tx :twap :account (:acct buyer) :market 2
                        :side 0 :qty 40 :slices 4 :every 40})
        ;; `:every` in BLOCKS, and this chain runs about five a second — so a
        ;; schedule of `:every 2` finishes in under two seconds, which is
        ;; faster than this script can sample it. The first version measured
        ;; that and called it "filled in one slice"; the schedule was working
        ;; and the test was too slow to see it.
        _ (wait 3000)
        t0 (GET (str "/twaps?w=w1&account=" (:acct buyer)))
        p0 (pos-of (:acct buyer))
        _ (println "submitted -> twaps" (pr-str (:twaps t0)) " position" p0)
        _ (wait 12000)
        p1 (pos-of (:acct buyer))
        t1 (GET (str "/twaps?w=w1&account=" (:acct buyer)))
        _ (println "after ~15s -> position" p1 " remaining" (:remaining (first (:twaps t1))))
        _ (wait 25000)
        p2 (pos-of (:acct buyer))
        t2 (GET (str "/twaps?w=w1&account=" (:acct buyer)))]
  (println "after ~40s -> position" p2 " twaps left" (count (:twaps t2)))
  (println)
  (cond
    (empty? (:twaps t0)) (println "INCONCLUSIVE — the TWAP was never accepted")
    (= 40 p0) (println "FAIL — it filled all at once in the submitting block")
    (not (pos? p1)) (println "INCONCLUSIVE — no slice had fired yet; the book may be empty")
    (>= p1 40) (println "FAIL — the whole quantity went through in one slice")
    (< p1 p2) (println "PASS — the TWAP filled progressively across blocks:" p0 "→" p1 "→" p2)
    :else (println "INCONCLUSIVE — position did not advance between samples:" p1 p2)))
