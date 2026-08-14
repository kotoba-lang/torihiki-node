(ns torihiki-node.views
  "The history the validator folds beside the exchange, and the shapes it has
  had on disk.

  ## Why this is its own namespace

  `torihiki-node.validator` cannot be loaded outside a Worker — its `deftype`
  closes over Durable Object state, and `script/loads.cljs` says so and skips
  it. So every function in it is checked by deploying it, and the functions
  that decide what a checkpoint CONTAINS are exactly the ones where deploying
  to find out is expensive: a wrong shape is not noticed until a replica
  restarts, and a replica restarts when the venue is already in trouble.

  These are pure, they are the shape decisions, and `script/views_check.cljs`
  runs them under nbb.

  ## What a view is

  The tape, the candle index and the refusal reasons. `torihiki.state` does
  not produce any of them; the validator's apply-fn folds them into the same
  map as the exchange because that map is what every replica derives
  identically from the same blocks — which is the point, a view assembled
  per replica would be four different tapes.

  They are NOT state: `torihiki.state/canonical-leaves` reads none of them, so
  the state root does not commit to them, so losing them costs history and not
  agreement. `torihiki.snapshot/view-keys` is where that is enforced — the
  state write drops them, and `checkpoint!` writes them separately.

  ## The shapes, in the order they were written

  `:candles` began as one market's vector, became a map keyed by market id,
  and in between was written by a checkpoint that still treated the map as a
  vector. All three are on disk right now, and `candles-by-market` is what
  reads them.")

(def view-keys
  "The keys that are history and must not ride in the state write.

  `torihiki.snapshot/view-keys` names the same three, and that is where a host
  is stopped from getting this wrong. It is named here too, and the reason is
  not belt and braces: `deps.edn` pins `torihiki` by git sha, so an engine fix
  reaches this Worker on the next REPIN and not before. A checkpoint that goes
  on carrying an unbounded tape until somebody remembers to move a sha is the
  bug still deployed, with a commit that says otherwise.

  Dropped from the state write and written separately by `checkpoint!`, which
  is where the measured failure is described."
  #{:tape :candles :refused})

(def ^:const default-market
  "Which market a candle vector from before the per-market split belongs to.

  Market 1, because market 1 is what a single-market checkpoint could have
  been about. `torihiki-node.validator/market-id` is this value — the read
  routes' default market and the market an old checkpoint is attributed to
  have to be the same one, and one literal is how that stays true."
  1)

(defn candles-by-market
  "The candle index, whatever shape it arrives in, as a map from market id to
  that market's candles.

  A MAP is the current shape and is returned as it is.

  A VECTOR OF CANDLES is a checkpoint from before candles were per market; it
  is `default-market`'s.

  A VECTOR OF PAIRS is what `(vec (take-last n cs))` produces once `cs` is a
  map: `[[1 [...]] [2 [...]]]`, whole markets rather than candles. It was
  written for as long as the checkpoint kept treating the index as a vector,
  and on the way back in it matched the vector branch above — so market 1's
  chart became a list of pairs and every other market lost its candles.

  The two vectors are told apart by their elements, which is the only thing
  that distinguishes them: a candle is a map, a pair is not."
  [cs]
  (cond
    (map? cs) cs
    (empty? cs) {}
    (every? #(and (sequential? %) (= 2 (count %))) cs) (into {} (map vec) cs)
    :else {default-market (vec cs)}))

(defn checkpoint-candles
  "The candle index cut down to the last `n` candles PER MARKET.

  The bound used to be `(vec (take-last n cs))`, written when `:candles` was
  one market's vector and left alone when it became a map. On a map that takes
  `n` ENTRIES — `n` whole markets, each holding up to the machine's full
  candle retention — so the number it wrote was bounded by how many markets
  exist and not by `n` at all. A bound applied to the wrong axis is worse than
  no bound, because nobody reads it twice."
  [n cs]
  (into {}
        (map (fn [[m v]] [m (vec (take-last n v))]))
        (candles-by-market cs)))

(defn absorb-refusals
  "This block's refusal reasons, appended to the running list and cut to `n`.

  `torihiki.state/apply-block` clears `:rejected` at the top of every block, so
  a fold that did not copy them out would end holding only the last block's —
  which reads as nothing ever having been refused. It copied them out and never
  trimmed: one entry per refused transaction, for the life of the chain, in the
  machine state, which is written whole into every checkpoint. The one value in
  there that only grows, growing fastest when something is already wrong."
  [n refused rejected]
  (into [] (take-last n (into (or refused []) (map :reason) rejected))))

(defn merge-views
  "Fold a history write back into a machine state.

  One definition because views arrive in three places — the `views:latest`
  key, a checkpoint from when they still rode inside the snapshot, and a
  peer's snapshot during an adopt — and each of those had its own `merge`, so
  the candle shape had to be got right three times.

  `merge` and not `assoc`: a snapshot from before any of this carries no views
  at all, and writing `nil` over a freshly restored machine would be the same
  loss with an extra step. Nothing outside `view-keys` is taken, because what
  arrives here is history and the state came from the snapshot."
  [ms views]
  (if (seq views)
    (merge ms (cond-> (select-keys views view-keys)
                (contains? views :candles) (update :candles candles-by-market)))
    ms))

(defn state-only
  "A machine state with the history taken out — what the state write carries.

  The inverse of `merge-views`, and the pair is the split: everything this
  removes is in the other write, and nothing it removes is under the state
  root."
  [ms]
  (apply dissoc ms view-keys))
