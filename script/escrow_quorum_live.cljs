
;; The escrow quorum, on the deployed chain.
;;
;; The observation source is still unsettled — a Cloudflare Worker cannot reach
;; THORChain (measured: four hosts do not resolve, the one that does refuses).
;; So this supplies the observations by hand and asks the only question that
;; part cannot answer for itself: **does the chain credit a deposit when, and
;; only when, a quorum of BONDED validators says the same thing?**
;;
;; Nothing here is money. Every transaction is what the watcher would submit.
;;
;; Measured 2026-08-14 against v3:
;;
;;   publisher weight  all four validators bonded
;;   after TWO   attestations   collateral 0
;;   after THREE attestations   collateral 4242
;;
;; Two of the four had no stake on the first attempt and their attestations
;; were ignored — which is the rule working, and is why the first run read
;; INCONCLUSIVE rather than PASS. Funding and bonding them made the quorum
;; reachable and the credit appeared, exactly once.
;;
;; Sequential on purpose: submitting in parallel raced the nonce reads and six
;; transactions came back `bad-nonce`, each having read the same next value.
(ns escrow-quorum-live
  (:require ["node:fs" :as fs] [promesa.core :as p]
            [torihiki.address :as addr] [torihiki.auth :as auth]))
(def base "https://torihiki-validator-v3.04-feasts-minded.workers.dev")
(def chain-id "torihiki-engi-devnet-1")
(defn b64 [b] (js/btoa (.apply js/String.fromCharCode nil (js/Uint8Array. b))))
(defn GET [p*] (p/let [r (js/fetch (str base p*)) j (.json r)] (js->clj j :keywordize-keys true)))
(defn POST [p* b] (p/let [r (js/fetch (str base p*) #js {:method "POST" :body (js/JSON.stringify (clj->js b))}) j (.json r)] (js->clj j :keywordize-keys true)))
(defn wait [ms] (p/create (fn [r _] (js/setTimeout r ms))))
(defn- seed-of [w] (js/Uint8Array. (js/Buffer.from (.trim (fs/readFileSync (str "/tmp/v3keys/" w ".seed") "utf8")) "base64")))
(defn- pair [w]
  (p/let [der (js/Uint8Array.from (concat [0x30 0x2e 0x02 0x01 0x00 0x30 0x05 0x06 0x03 0x2b 0x65 0x70 0x04 0x22 0x04 0x20] (array-seq (seed-of w))))
          sk (js/crypto.subtle.importKey "pkcs8" der #js {:name "Ed25519"} true #js ["sign"])
          jwk (js/crypto.subtle.exportKey "jwk" sk)
          pub (.toString (js/Buffer.from (aget jwk "x") "base64url") "base64")]
    {:sk sk :pub pub :acct (addr/derive pub)}))
(defn- tx! [{:keys [sk pub acct]} n t]
  (p/let [pl (auth/signing-payload chain-id acct n t)
          sg (js/crypto.subtle.sign #js {:name "Ed25519"} sk (.encode (js/TextEncoder.) pl))]
    (POST "/tx?w=w1" {:tx t :account acct :nonce n :pubkey pub :sig (b64 sg)})))
(defn- nonce-of [a] (p/let [x (GET (str "/account?w=w1&id=" a))] (:next-nonce x)))

(p/let [ks (p/all (map pair ["w1" "w2" "w3" "w4"]))
        need (filterv #(#{19739237290277 22369017383925} (:acct %)) ks)
        _ (p/run! (fn [k] (p/let [_ (POST "/faucet?w=w1" {:account (:acct k)})] (wait 11000))) need)
        _ (p/run! (fn [k] (p/let [n (nonce-of (:acct k))
                                  r (tx! k n {:tx :bond :account (:acct k) :validator (:acct k) :amount 1000000})]
                            (println " bond" (:acct k) (pr-str r)) (wait 11000))) need)
        st (GET "/stake?w=w1")
        _ (println "publisher weight:" (pr-str (:publisher-weight st)))
        target 888888
        txid (str "PROBE2-" (.slice (str (.random js/Math)) 2 9))
        three (vec (take 3 ks))
        _ (println "attesting" txid "→" target)
        _ (p/run! (fn [k] (p/let [n (nonce-of (:acct k))
                                  r (tx! k n {:tx :deposit-attest :account (:acct k) :txid txid
                                              :credit target :amount 4242 :asset "ETH.ETH"})]
                            (println "  " (:acct k) (pr-str r)) (wait 11000)))
                  (subvec three 0 2))
        two (GET (str "/account?w=w1&id=" target))
        _ (println "after TWO  :" (:collateral two))
        _ (p/let [k (nth three 2) n (nonce-of (:acct k))]
            (tx! k n {:tx :deposit-attest :account (:acct k) :txid txid
                      :credit target :amount 4242 :asset "ETH.ETH"}))
        _ (wait 13000)
        fin (GET (str "/account?w=w1&id=" target))]
  (println "after THREE:" (:collateral fin))
  (println (if (and (= 0 (or (:collateral two) 0)) (= 4242 (:collateral fin)))
             "PASS — 2 名では credit されず、3 名で ちょうど 1 度 credit された"
             (str "NOT YET — two=" (:collateral two) " three=" (:collateral fin)))))
