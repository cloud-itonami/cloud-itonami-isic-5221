(ns landtransport.phase
  "Phase 0->3 staged rollout for the land-transport-support actor.

    Phase 0  read-only        -- no writes, still governor-gated.
    Phase 1  assisted-intake  -- safety-scope intake allowed, every
                                 write needs human approval.
    Phase 2  assisted-verify  -- adds safety-scope-verification writes,
                                 still approval.
    Phase 3  supervised auto  -- governor-clean, high-confidence
                                 `:safety-scope/intake` (no dispatch
                                 risk yet) may auto-commit.
                                 `:dispatch/authorize`/
                                 `:reconciliation/publish` NEVER
                                 auto-commit, at any phase.

  `:dispatch/authorize`/`:reconciliation/publish` are deliberately
  ABSENT from every phase's `:auto` set, including phase 3 -- a
  permanent structural fact, not a rollout milestone still to come.
  Authorizing a toll-lane change, a recovery job, or a terminal-slot
  operation dispatch, and publishing the multi-carrier reconciliation
  record for that dispatch, are the two real-world physical / legal
  acts this actor performs; both are always a human operator's call.
  `landtransport.governor`'s `:dispatch/authorize`/
  `:reconciliation/publish` high-stakes gate enforces the same
  invariant independently -- two layers, not one, agree on this. Like
  every prior sibling's phase 3 `:auto` set, this domain has only ONE
  member (`:safety-scope/intake`) -- no separate no-risk 'file'
  lifecycle distinct from the land-dispatch record itself.")

(def read-ops  #{})
(def write-ops #{:safety-scope/intake :safety-scope/verify :dispatch/authorize :reconciliation/publish})

;; NOTE the invariant: `:dispatch/authorize`/`:reconciliation/publish`
;; are members of `write-ops` (governor-gated like any write) but are
;; NEVER members of any phase's `:auto` set below. Do not add them there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"        :writes #{}                                                        :auto #{}}
   1 {:label "assisted-intake"  :writes #{:safety-scope/intake}                                     :auto #{}}
   2 {:label "assisted-verify"  :writes #{:safety-scope/intake :safety-scope/verify}                :auto #{}}
   3 {:label "supervised-auto"  :writes write-ops
      :auto #{:safety-scope/intake}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:dispatch/authorize`/`:reconciliation/publish` are never auto-
    eligible at any phase, so they always escalate once the governor
    clears them (or hold if the governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a Land Transport Support Governor verdict to a base disposition before
  the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
