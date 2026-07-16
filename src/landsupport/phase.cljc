(ns landsupport.phase
  "Phase 0->3 staged rollout -- the land-transport-support-operations
  analog of `cloud-itonami-isic-3512`'s `energy.phase`.

    Phase 0  read-only        -- no writes, still governor-gated.
    Phase 1  assisted-logging -- facility-record logging allowed,
                                 every write needs human approval.
    Phase 2  assisted-coord   -- adds maintenance-scheduling +
                                 supply-order-coordination writes,
                                 still approval.
    Phase 3  supervised auto  -- governor-clean, high-confidence
                                 `:log-facility-record` (no safety/
                                 cost risk) may auto-commit.
                                 `:coordinate-supply-order` may ALSO
                                 auto-commit at phase 3 IF the governor
                                 itself does not escalate it (i.e. its
                                 cost estimate is at or below
                                 `landsupport.governor/high-cost-
                                 threshold` -- the governor's own
                                 high-cost check is what actually gates
                                 this, not this phase table alone: two
                                 independent layers, deliberately).

  `:flag-structural-safety-concern` is deliberately ABSENT from every
  phase's `:auto` set, including phase 3 -- a permanent structural
  fact, not a rollout milestone still to come. Surfacing a structural/
  traffic-safety concern is always a human structural-safety
  engineer's call to triage; `landsupport.governor`'s
  `always-escalate-ops` enforces the same invariant independently --
  two layers, not one, agree on this.

  There is NO op in this actor's closed allowlist
  (`landsupport.governor/allowed-ops`) that itself finalizes a
  structural-safety clearance, dispatches a toll lane, or operates
  facility equipment -- so that category of real-world decision has NO
  auto-commit path at any phase, by construction, independent of this
  table. `landsupport.governor`'s HARD check 4
  (`structural-safety-clearance-finalization-violations`) is a third,
  defense-in-depth layer against a proposal that tries to word its way
  into one anyway.")

(def read-ops  #{})
(def write-ops #{:log-facility-record :schedule-facility-maintenance
                 :flag-structural-safety-concern :coordinate-supply-order})

;; NOTE the invariant: `:flag-structural-safety-concern` is a member of
;; `write-ops` (governor-gated like any write) but is NEVER a member of
;; any phase's `:auto` set below. Do not add it there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"        :writes #{}                                                       :auto #{}}
   1 {:label "assisted-logging" :writes #{:log-facility-record}                                    :auto #{}}
   2 {:label "assisted-coord"   :writes #{:log-facility-record :schedule-facility-maintenance
                                          :coordinate-supply-order}                                 :auto #{}}
   3 {:label "supervised-auto"  :writes write-ops
      :auto #{:log-facility-record :coordinate-supply-order}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:flag-structural-safety-concern` is never auto-eligible at any
    phase, so it always escalates once the governor clears it (or
    holds if the governor doesn't).
  - `:coordinate-supply-order` IS listed in phase 3's `:auto` set, but
    the governor's own high-cost check already forced `:escalate`
    disposition for above-threshold orders before this gate ever
    runs -- so a governor disposition of `:commit` reaching this gate
    for `:coordinate-supply-order` can only be a genuinely low-cost,
    clean order."
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
  "Map a Land Transport Support Governor verdict to a base disposition
  before the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
