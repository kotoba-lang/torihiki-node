;; A contract, on the DEPLOYED chain.
;;
;; `torihiki.evm.interp` and the JSON-RPC surface both run in the validator
;; Worker now, so this asks the question that matters: does a contract deployed
;; through consensus answer the same on a chain nobody is running locally.
(ns evm-live-e2e
  (:require [promesa.core :as p]
            [torihiki.address :as addr]
            [torihiki.auth :as auth]
            [torihiki.keccak :as kc]))

(def base (or (some-> js/process .-env .-TORIHIKI_BASE)
              "https://torihiki-validator-v2.04-feasts-minded.workers.dev"))
(def chain-id "torihiki-engi-devnet-1")
(defn b64 [b] (js/btoa (.apply js/String.fromCharCode nil (js/Uint8Array. b))))
(defn GET [p*] (p/let [r (js/fetch (str base p*)) j (.json r)]
                 (js->clj j :keywordize-keys true)))
(defn POST [p* b] (p/let [r (js/fetch (str base p*)
                                      #js {:method "POST"
                                           :body (js/JSON.stringify (clj->js b))})
                          j (.json r)]
                    (js->clj j :keywordize-keys true)))
(defn wait [ms] (p/create (fn [r _] (js/setTimeout r ms))))

;; WebCrypto with a RAW public key, which is what the chain imports.
;;
;; The first version used node:crypto and exported SPKI/DER. Both are Ed25519
;; and both verify — against each other. The node imports `"raw"`, so a DER
;; key is 44 bytes where it expects 32 and every signature came back
;; `bad-signature`, which reads as a key that does not match and was a key
;; that was not the same SHAPE.
(defonce keys- (atom nil))

(defn sign! [payload]
  (p/let [{:keys [sk]} @keys-
          s (js/crypto.subtle.sign #js {:name "Ed25519"} sk
                                   (.encode (js/TextEncoder.) payload))]
    (b64 s)))

(defn tx! [nonce t]
  (p/let [{:keys [pub acct]} @keys-
          payload (auth/signing-payload chain-id acct nonce t)
          sig (sign! payload)]
    (POST "/tx?w=w1" {:tx t :account acct :nonce nonce :pubkey pub :sig sig})))

;; PUSH1 42, PUSH1 0, MSTORE, PUSH1 32, PUSH1 0, RETURN
(def code "602a60005260206000f3")
(def salt (apply str (repeat 64 "0")))

(defn addr-of [n]
  (let [h (.toString n 16)]
    (str "0x" (apply str (concat (repeat (- 40 (count h)) "0") h)))))

(p/let [kp (js/crypto.subtle.generateKey #js {:name "Ed25519"} true #js ["sign" "verify"])
        raw (js/crypto.subtle.exportKey "raw" (.-publicKey kp))
        pub (b64 raw)
        acct (addr/derive pub)
        _ (reset! keys- {:sk (.-privateKey kp) :pub pub :acct acct})
        _ (println "account" acct)
        ;; The faucet first: it is what binds the account to this key, and a
        ;; nonce for an account the chain has never seen is a guess.
        _ (POST "/faucet?w=w1" {:account acct})
        _ (wait 12000)
        a0 (GET (str "/account?w=w1&a=" acct))
        ;; A known-good transaction first, through the same signing path. If
        ;; this one is refused too, the client is wrong; if only the deploy is,
        ;; the difference is the deploy.
        ctl (tx! (:next-nonce a0) {:tx :set-leverage :account acct :market 1 :leverage 2})
        _ (println "control /tx →" (pr-str ctl))
        _ (wait 12000)
        a1 (GET (str "/account?w=w1&a=" acct))
        r (tx! (:next-nonce a1) {:tx :evm-deploy :account acct
                                 :code code :salt salt})
        _ (println "/tx →" (pr-str r))
        _ (wait 15000)
        want (kc/create2-address (addr-of acct) salt code)
        _ (println "expected contract" want)
        got (POST "/rpc?w=w1" {:jsonrpc "2.0" :id 1 :method "eth_getCode"
                               :params [want]})
        _ (println "eth_getCode →" (:result got))
        out (POST "/rpc?w=w1" {:jsonrpc "2.0" :id 2 :method "eth_call"
                               :params [{:to want :data "0x"}]})]
  (println "eth_call  →" (:result out))
  (println (if (= "0x000000000000000000000000000000000000000000000000000000000000002a"
                  (:result out))
             "PASS — a contract deployed through consensus ran on the deployed chain"
             "FAIL")))
