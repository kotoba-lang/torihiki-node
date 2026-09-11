;; A signing client for the node. nbb, per the workspace's script-host rule.
;;
;; It computes the signing payload with torihiki.auth — the SAME function the
;; node uses — rather than reimplementing the format. A client that spells the
;; payload out by hand is a second implementation of a consensus rule, and the
;; first time the two disagree every signature fails for a reason that looks
;; like cryptography and is not.
(ns client
  (:require ["node:crypto" :as nc]
            [torihiki.auth :as auth]))

(def base (or (first *command-line-args*) "http://localhost:8787"))
(def chain "torihiki-devnet-1")

(defn keypair []
  (let [{:keys [publicKey privateKey]}
        (js->clj (nc/generateKeyPairSync "ed25519") :keywordize-keys true)
        ;; the raw 32-byte key is the tail of the SPKI DER
        spki (.export publicKey #js {:type "spki" :format "der"})
        raw (.subarray spki (- (.-length spki) 32))]
    {:priv privateKey :pub (.toString raw "base64")}))

(defn sign [priv payload]
  (.toString (nc/sign nil (js/Buffer.from payload "utf8") priv) "base64"))

(defn submit! [{:keys [priv pub]} account nonce tx]
  (let [payload (auth/signing-payload chain account nonce tx)
        body #js {:tx (clj->js tx) :account account :nonce nonce
                  :pubkey pub :sig (sign priv payload)}]
    (-> (js/fetch (str base "/tx")
                  #js {:method "POST"
                       :headers #js {"content-type" "application/json"}
                       :body (js/JSON.stringify body)})
        (.then #(.json %))
        (.then #(js->clj % :keywordize-keys true)))))

(defn get! [path]
  (-> (js/fetch (str base path)) (.then #(.json %))
      (.then #(js->clj % :keywordize-keys true))))

(defn -main []
  ;; Fresh account ids per run. An id is bound to the key that first claimed
  ;; it and the binding is immutable, so reusing ids across runs with new
  ;; keys is correctly refused with :wrong-key — which is the rule working,
  ;; not the demo failing.
  (let [maker (keypair) taker (keypair)
        base (+ 1000 (rand-int 900000))
        a-maker base
        a-taker (inc base)]
    (-> (js/Promise.resolve)
        (.then #(submit! maker a-maker 1 {:tx :deposit :amount 100000000}))
        (.then #(do (println "deposit maker :" %) (submit! taker a-taker 1 {:tx :deposit :amount 100000000})))
        (.then #(do (println "deposit taker :" %) (submit! maker a-maker 2 {:tx :oracle :market 1 :price 68000})))
        (.then #(do (println "oracle        :" %)
                    (submit! maker a-maker 3 {:tx :order :market 1 :side 1 :level 68010 :qty 500})))
        (.then #(do (println "maker ask     :" %)
                    (submit! maker a-maker 4 {:tx :order :market 1 :side 0 :level 67990 :qty 500})))
        (.then #(do (println "maker bid     :" %)
                    (submit! taker a-taker 2 {:tx :order :market 1 :side 0 :level 68010 :qty 120})))
        (.then #(do (println "taker buy     :" %)
                    ;; replay the same nonce — must be refused
                    (submit! taker a-taker 2 {:tx :order :market 1 :side 0 :level 68010 :qty 120})))
        (.then #(do (println "replay        :" %)
                    ;; impersonate account 11 with the taker's key
                    (submit! taker a-maker 5 {:tx :withdraw :amount 100000000})))
        (.then #(do (println "impersonation :" %) (get! "/head")))
        (.then #(do (println "head          :" %) (get! (str "/account?id=" a-taker))))
        (.then #(println "taker account :" %))
        (.catch #(println "ERROR" %)))))

(-main)
