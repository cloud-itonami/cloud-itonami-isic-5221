(ns landsupport.governor
  "Land Transport Support Governor -- the independent compliance layer
  that earns the Land Transport Support Advisor the right to commit.
  The LLM has no notion of which facilities are actually registered
  and verified by an independent permit/registration authority, when a
  proposal has quietly drifted outside the closed op-allowlist this
  actor is scoped to, or when a proposal is trying to word its way
  into finalizing a structural-safety-clearance decision that is
  categorically NOT this actor's authority to make, so this MUST be a
  separate system able to *reject* a proposal and fall back to HOLD --
  the land-transport-support-operations analog of
  `cloud-itonami-isic-3512`'s Grid Policy Governor.

  This actor is deliberately scoped to OPERATIONS COORDINATION for
  land-transport-infrastructure-support facilities (toll roads,
  bridges, tunnels, parking facilities, bus terminals): usage/
  transaction data logging, maintenance scheduling PROPOSALS,
  surfacing structural-safety concerns for human review, and
  maintenance-materials procurement PROPOSALS. It is NEVER the
  authority that finalizes a structural-safety clearance, dispatches a
  toll lane, or operates facility equipment -- see README `Scope`.

  Four checks, in priority order, ALL HARD violations: a human
  approver CANNOT override them (you don't get to approve your way
  past an unregistered/unverified facility, an out-of-allowlist op, a
  non-`:propose` effect, or a proposal that tries to finalize a
  structural-safety clearance). The confidence/actuation gate is SOFT
  (it asks a human to look; the human may approve) -- but see
  `landsupport.phase`: `:flag-structural-safety-concern` NEVER auto-
  commits at any phase, and NO op in this actor's closed allowlist is
  itself a structural-safety-clearance-finalization act, so that
  specific real-world decision never has an auto-commit path at all,
  by construction -- two independent layers (this governor's HARD
  check 4, and the simple fact that no such op exists in the
  allowlist) agree.

    1. Facility unregistered/unverified -- the target facility must be
       independently verified/registered (by an EXTERNAL permit/
       registration authority this actor never writes to) before ANY
       action against it, re-derived from the facility's own
       `:registered?`/`:verified?` fields every time, never from
       proposal self-report.
    2. Closed op-allowlist            -- `:op` must be one of the four
                                          ops this actor is scoped to;
                                          anything else is rejected
                                          outright. Evaluated
                                          independently of check 1 (all
                                          four checks run and their
                                          violations are unioned, so an
                                          out-of-allowlist op is
                                          rejected regardless of the
                                          target facility's own
                                          registration status).
    3. Effect not `:propose`          -- this actor NEVER performs a
                                          real-world actuation itself;
                                          every commit is a proposal
                                          record. Any other `:effect`
                                          is rejected outright.
    4. Structural-safety-clearance
       finalization scope-exclusion    -- ANY proposal (regardless of
                                          which allowlisted op it
                                          claims to be) whose text
                                          reads as finalizing/
                                          certifying a structural-
                                          safety clearance is a HARD,
                                          permanent, un-overridable
                                          block. See `finalization-
                                          phrases` docstring below for
                                          why these are phrased as the
                                          finalization ACTION rather
                                          than a bare noun like
                                          'safety' or 'structural'.

  Two more (SOFT) escalation triggers, evaluated after the HARD checks
  clear: `:flag-structural-safety-concern` ALWAYS escalates to a human
  (surfacing a structural/traffic-safety concern is never something
  this actor auto-dismisses OR auto-commits), and `:coordinate-supply-
  order` escalates when its cost estimate is above
  `high-cost-threshold`. Confidence below `confidence-floor` escalates
  regardless of op."
  (:require [landsupport.store :as store]))

(def confidence-floor 0.6)
(def high-cost-threshold
  "Maintenance-materials procurement proposals at or below this
  estimated cost may reach auto-commit (phase 3, governor-clean);
  above it, always escalate to a human -- see README `Actuation`."
  50000)

(def allowed-ops
  "The CLOSED set of ops this actor may ever propose. Anything else is
  a HARD, permanent rejection -- see `closed-allowlist-violations`."
  #{:log-facility-record :schedule-facility-maintenance
    :flag-structural-safety-concern :coordinate-supply-order})

(def always-escalate-ops
  "Ops that ALWAYS escalate to a human once the HARD checks clear,
  regardless of confidence or phase. `:flag-structural-safety-concern`
  surfaces a structural/traffic-safety concern for human review -- an
  actor with no structural-safety authority must never auto-dismiss OR
  auto-accept one on its own."
  #{:flag-structural-safety-concern})

;; ----------------------------- checks -----------------------------

(defn- facility-unverified-violations
  "Check 1: the target facility must exist AND be independently
  `:registered?`/`:verified?` -- re-derived from the store every time,
  never trusted from the proposal itself."
  [{:keys [subject]} st]
  (let [f (store/facility st subject)]
    (cond
      (nil? f)
      [{:rule :facility-unverified
        :detail (str subject " は施設台帳に存在しません")}]

      (not (:registered? f))
      [{:rule :facility-unverified
        :detail (str subject " は許認可当局に未登録の施設です")}]

      (not (:verified? f))
      [{:rule :facility-unverified
        :detail (str subject " は独立検証が未完了の施設です")}]

      :else [])))

(defn- closed-allowlist-violations
  "Check 2: `:op` must be a member of `allowed-ops`. This actor is
  scoped to exactly four coordination ops -- anything else (including
  any op that would itself constitute dispatching a toll lane,
  operating facility equipment, or finalizing a structural-safety
  clearance) is rejected outright, independently of the facility's own
  registration status."
  [{:keys [op]}]
  (when-not (contains? allowed-ops op)
    [{:rule :op-not-allowed
      :detail (str op " はこのアクターの許可された操作範囲外です")}]))

(defn- effect-not-propose-violations
  "Check 3: this actor NEVER performs a real-world actuation itself --
  every commit this actor can ever make is `:effect :propose`. Any
  other effect value is rejected outright."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str "effect は :propose のみ許可 (got " (pr-str (:effect proposal)) ")")}]))

(def ^:private finalization-phrases
  "Structural-safety-clearance-finalization ACTION phrases (EN+JA).

  **Critical pattern (this fleet's recurring self-tripping bug
  class)**: these patterns are phrased as the finalization/execution
  ACTION ('finalize the structural-safety clearance', '構造安全性
  クリアランスを確定/認定/発行'), never as a bare noun like 'safety' or
  'structural'. A bare-noun pattern would accidentally match inside
  the mock advisor's own DEFAULT rationale text for a perfectly
  legitimate `:flag-structural-safety-concern` proposal (whose
  rationale legitimately talks ABOUT a structural/safety concern
  without ever finalizing/certifying a clearance) -- causing this
  actor to self-block on its own happy path. See
  `test/landsupport/scope_exclusion_test.clj` for the dedicated
  regression test asserting the default mock-advisor proposals for
  ALL FOUR allowlisted ops never trip this check."
  [#"(?i)finaliz(e|ing|ed).{0,40}structural.?safety.?clearance"
   #"(?i)certif(y|ies|ied|ying).{0,40}structurally.?safe"
   #"(?i)issu(e|ing|ed).{0,40}structural.?safety.?clearance"
   #"(?i)clearance.{0,20}(is\s+)?finaliz(ed|e)"
   #"構造(的)?安全性?クリアランスを(確定|認定|発行|承認)"
   #"構造安全(性)?を(確定|認定)"])

(defn- structural-safety-clearance-finalization-violations
  "Check 4: HARD, permanent, un-overridable. Applies to the proposal's
  full text (`:summary`/`:rationale`/`:cites`/stringified `:value`)
  regardless of which op it claims to be -- this actor has no
  structural-safety authority, full stop, so no phrasing of any
  proposal may finalize/certify a structural-safety clearance."
  [proposal]
  (let [text (str (:summary proposal) " " (:rationale proposal) " "
                  (pr-str (:cites proposal)) " " (pr-str (:value proposal)))]
    (when (some #(re-find % text) finalization-phrases)
      [{:rule :structural-safety-clearance-finalization
        :detail "構造安全性クリアランスの確定/認定はこのアクターの権限外 -- 常にハードブロック"}])))

(defn check
  "Censors a Land Transport Support Advisor proposal against the
  governor rules. Returns {:ok? bool :violations [..] :confidence c
  :escalate? bool :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (facility-unverified-violations request st)
                           (closed-allowlist-violations request)
                           (effect-not-propose-violations proposal)
                           (structural-safety-clearance-finalization-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        always-escalate? (boolean (always-escalate-ops (:op request)))
        high-cost? (and (= :coordinate-supply-order (:op request))
                        (number? (get-in proposal [:value :cost-estimate]))
                        (> (get-in proposal [:value :cost-estimate]) high-cost-threshold))
        stakes? (or always-escalate? high-cost?)
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
