;; Every namespace in this node, read and required.
;;
;;   npx nbb -cp "src:$(clojure -Spath)" script/loads.cljs
;;
;; ## Why here and not only in `torihiki`
;;
;; `torihiki` got this check first, and the two files that actually broke that
;; day were in THIS repository:
;;
;;   `validator.cljs` — a bare `"` inside a docstring, pushed and merged with
;;   a build that did not compile.
;;   `standalone.cljs` — the same mistake, in a docstring about testing, which
;;   left the node unable to start for four commits because the change was
;;   "only a docstring" and nothing was re-run.
;;
;; A gate that covers the code you were careful about and not the code you
;; were careless with is a gate aimed at the wrong place.
;;
;; `worker.cljs` and `validator.cljs` are Cloudflare Workers: they close over
;; `env` and Durable Object globals that do not exist under nbb, so requiring
;; them here would fail for a reason that is not the one this checks for.
;; `shadow-cljs release` is their reader — the point is that it must be RUN,
;; and `deploy/equivalence-check.sh` plus this file together mean neither half
;; ships unread.
(ns loads
  (:require [torihiki-node.standalone]))

(println "LOADS: pass — the standalone reads and requires on ClojureScript")
