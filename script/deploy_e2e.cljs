(ns deploy-e2e
  (:require ["node:crypto" :as nc]
            ["@noble/hashes/sha2.js" :refer [sha256]]
            [promesa.core :as p]
            [torihiki.auth :as auth]
            [torihiki.keccak :as kc]))

(def base
  ;; The DEPLOYED chain, not a local one. A contract that only runs where the
  ;; test runs is a contract nobody can call.
  (or (some-> js/process .-env .-TORIHIKI_BASE)
      "https://torihiki-validator-v2.04-feasts-minded.workers.dev"))
(def chain-id
  ;; The standalone's default. A chain id that does not match the node's is a
  ;; signature over a different payload, which comes back as `bad-signature`
  ;; and reads as a key problem.
  (or (some-> js/process .-env .-CHAIN_ID) "torihiki-standalone-1"))
(defn b64 [b] (.toString b "base64"))
(defn derive-account [pub]
  (let [d (sha256 (js/Buffer.from pub "base64"))]
    (+ 100000 (mod (reduce (fn [a i] (+ (* a 256) (aget d i))) 0 (range 6)) 35184372088832))))

(def kp (nc/generateKeyPairSync "ed25519"))
(def pub (b64 (.export (.-publicKey kp) #js {:format "der" :type "spki"})))
(def acct (derive-account pub))

;; PUSH1 42, PUSH1 0, MSTORE, PUSH1 32, PUSH1 0, RETURN
(def code "602a60005260206000f3")
(def salt (apply str (repeat 64 "0")))

(defn addr-of [n]
  (let [h (.toString n 16)]
    (str "0x" (apply str (concat (repeat (- 40 (count h)) "0") h)))))

(p/let [tx {:tx :evm-deploy :account acct :code code :salt salt}
        payload (auth/signing-payload chain-id acct 1 tx)
        sig (b64 (nc/sign nil (js/Buffer.from payload "utf8") (.-privateKey kp)))
        r0 (js/fetch (str base "/tx?w=w1") #js {:method "POST"
                                          :body (js/JSON.stringify
                                                 (clj->js {:tx tx :account acct :nonce 1
                                                           :pubkey pub :sig sig}))})
        t0 (.text r0)
        _ (println "/tx →" t0)
        _ (p/create (fn [r _] (js/setTimeout r 12000)))
        want (kc/create2-address (addr-of acct) salt code)
        _ (println "account" acct "→ contract" want)
        r (js/fetch (str base "/rpc?w=w1")
                    #js {:method "POST"
                         :body (js/JSON.stringify
                                (clj->js {:jsonrpc "2.0" :id 1 :method "eth_getCode"
                                          :params [want]}))})
        j (.json r)
        _ (println "eth_getCode →" (.-result j))
        r2 (js/fetch (str base "/rpc?w=w1")
                     #js {:method "POST"
                          :body (js/JSON.stringify
                                 (clj->js {:jsonrpc "2.0" :id 2 :method "eth_call"
                                           :params [{:to want :data "0x"}]}))})
        j2 (.json r2)]
  (println "eth_call  →" (.-result j2))
  (println (if (= "0x000000000000000000000000000000000000000000000000000000000000002a"
                  (.-result j2))
             "PASS — deployed bytecode ran over JSON-RPC and returned 42"
             "FAIL")))
