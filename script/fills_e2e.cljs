;; A real fill between two accounts on the DEPLOYED chain, seen from both
;; sides. A maker and a taker are on OPPOSITE sides of the same print;
;; reporting the taker's side to both would tell one of them the opposite of
;; what happened.
(ns fills-e2e
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
(p/let [maker (mk) taker (mk)
        _ (println "maker" (:acct maker) " taker" (:acct taker))
        _ (POST "/faucet?w=w1" {:account (:acct maker)})
        _ (POST "/faucet?w=w1" {:account (:acct taker)})
        _ (wait 9000)
        nm (nonce-of (:acct maker))
        ;; maker rests an ask at 100 on market 2
        _ (tx maker nm {:tx :order :account (:acct maker) :market 2 :side 1 :level 100 :qty 2 :flags 0})
        _ (wait 9000)
        nt (nonce-of (:acct taker))
        ;; taker crosses it with a buy
        _ (tx taker nt {:tx :order :account (:acct taker) :market 2 :side 0 :level 100 :qty 2 :flags 0})
        _ (wait 10000)
        fm (GET (str "/fills?w=w1&account=" (:acct maker)))
        ft (GET (str "/fills?w=w1&account=" (:acct taker)))
        t2 (GET "/trades?w=w1&m=2&n=5")
        t1 (GET "/trades?w=w1&m=1&n=5")]
  (println "maker fills:" (pr-str (:fills fm)))
  (println "taker fills:" (pr-str (:fills ft)))
  (println "market 2 prints:" (count (:trades t2)) " market 1 prints:" (count (:trades t1)))
  (println)
  (let [m (first (:fills fm)) t (first (:fills ft))]
    (cond
      (or (nil? m) (nil? t)) (println "FAIL — the fill is missing from one side")
      (not= 2 (:m m)) (println "FAIL — the fill is tagged with the wrong market:" (:m m))
      (= (:side m) (:side t)) (println "FAIL — both sides reported the same direction:" (:side m))
      (not= "maker" (:role m)) (println "FAIL — the resting order was not the maker")
      (not= "taker" (:role t)) (println "FAIL — the crossing order was not the taker")
      :else (println "PASS — both sides see the fill, on market 2, from their own side"))))
