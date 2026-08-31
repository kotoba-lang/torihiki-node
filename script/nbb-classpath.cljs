;; The classpath `script/tests-on-nbb.cljs` needs, built from the deps.edn
;; pins without starting a JVM.
;;
;;   node -e ''                       # nothing to install
;;   nbb script/nbb-classpath.cljs    # prints one classpath on stdout
;;   nbb --classpath "$(nbb script/nbb-classpath.cljs)" script/tests-on-nbb.cljs
;;
;; ## Why not `clojure -Spath`
;;
;; Because that is the JVM, and the point of the nbb suite is to run where no
;; JVM does. `clojure -Spath` also resolves Maven coordinates this classpath
;; has no use for.
;;
;; ## Why the pins and not the checkouts
;;
;; A sibling checkout sits at whatever commit `west` last moved it to, which
;; is not necessarily the `:git/sha` in deps.edn. Measured on 2026-08-31 all
;; three direct dependencies were at a different commit than the pin. Building
;; the classpath from checkouts would run the suite against code the JVM suite
;; is not running against, and the two runs would then disagree for a reason
;; that is not a runtime difference at all.
;;
;; So each pin is extracted with `git archive <sha>` into `.nbb-deps/<name>/`.
;; The checkout is used only as an object store.
;;
;; ## It refuses rather than returning a short classpath
;;
;; An unresolvable pin exits 2 with the name and sha on stderr. A short
;; classpath would make `tests-on-nbb` fail to require a namespace, which
;; reads like a code error rather than a missing dependency -- and if it were
;; short in a way that still loaded, it would run a subset and report a pass.
(ns nbb-classpath
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [nbb.core :refer [*file*]]
            ["fs" :as fs]
            ["path" :as path]
            ["child_process" :as cp]))

(def repo (path/resolve (path/join (path/dirname *file*) "..")))
(def cache (path/join repo ".nbb-deps"))

;; Where to look for the object store holding a pinned commit. `west` puts
;; every project at `orgs/<org>/<name>`, so the superproject root is four
;; levels up from this repo; `KOTOBA_CHECKOUTS` overrides that for a layout
;; that is not this workspace's.
(defn- checkout-dir [nm]
  (or (some-> (aget js/process.env "KOTOBA_CHECKOUTS") (path/join nm))
      (path/join repo ".." nm)))

(defn- run [cmd]
  (try {:ok (str (cp/execSync cmd #js{:stdio #js["ignore" "pipe" "ignore"]}))}
       (catch :default e {:err (.-message e)})))

(defn- extract! [nm sha]
  (let [dest (path/join cache nm)]
    (if (fs/existsSync dest)
      dest
      (let [co (checkout-dir nm)]
        (when-not (fs/existsSync co)
          (js/console.error (str "unresolvable: " nm " @ " sha " — no checkout at " co))
          (js/process.exit 2))
        (when (:err (run (str "git -C " co " cat-file -e " sha "^{commit}")))
          (js/console.error (str "unresolvable: " nm " @ " sha " — commit not in " co))
          (js/process.exit 2))
        (fs/mkdirSync dest #js{:recursive true})
        (when-let [e (:err (run (str "git -C " co " archive " sha " | tar -x -C " dest)))]
          (js/console.error (str "extract failed: " nm " @ " sha " — " e))
          (js/process.exit 2))
        dest))))

(defn- deps-of [dir]
  (let [f (path/join dir "deps.edn")]
    (when (fs/existsSync f)
      ;; `:deps` only. An alias's `:extra-deps` is the JVM test runner and
      ;; friends, which this path does not use and cannot resolve.
      (:deps (edn/read-string (str (fs/readFileSync f "utf8")))))))

(defn- walk! [dir seen acc]
  (doseq [[coord v] (deps-of dir)]
    (when-let [sha (:git/sha v)]
      (let [nm (name coord)]
        (when-not (@seen nm)
          (swap! seen conj nm)
          (let [d (extract! nm sha)]
            (swap! acc conj (path/join d "src"))
            (walk! d seen acc)))))))

(let [seen (atom #{})
      acc (atom [])]
  (walk! repo seen acc)
  (when (empty? @acc)
    (js/console.error "no git dependencies resolved — deps.edn was not read")
    (js/process.exit 2))
  (println (str/join ":" (concat ["src" "test"] @acc))))
