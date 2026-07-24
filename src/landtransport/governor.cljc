(ns landtransport.governor
  "Land Transport Support Governor -- the independent compliance layer
  that earns the Land Transport Support Advisor the right to commit.
  The LLM has no notion of jurisdictional toll/terminal safety-scope
  law, whether a recovery job's vehicle-condition check was actually
  completed, whether a terminal-slot operation's evidence was actually
  verified, whether a recovery job's recovered vehicle actually fits
  the tow vehicle's rated capacity, or when a proposal stops being a
  draft and becomes a real-world dispatch authorization or
  reconciliation-record publication, so this MUST be a separate system
  able to *reject* a proposal and fall back to HOLD.

  Like `terminal.governor` (cloud-itonami-isic-5210), this land-
  transport-support vertical has NO pre-existing capability library to
  delegate to -- so the recovery-capacity check (recovered vehicle
  weight vs tow-vehicle rated capacity) is a pure function defined in
  `landtransport.registry` and called directly here, the SAME 'reuse a
  capability library's own validated function' discipline
  `retailops.governor`'s ean13 check establishes, here applied to this
  vertical's OWN pure registry functions rather than a separate
  library.

  `:itonami.blueprint/governor` is `:land-transport-support-governor`,
  already declared in this repo's `blueprint.edn` -- a fresh independent
  build following the SAME governed-actor architecture (langgraph
  StateGraph + independent Governor + Phase 0->3 rollout) established
  by `cloud-itonami-isic-6511` and applied by every prior sibling
  including `cloud-itonami-isic-5210`.

  Six checks, in priority order, ALL HARD violations: a human approver
  CANNOT override them. The confidence/actuation gate (item 7 below) is
  SOFT: it asks a human to look (low confidence / high-stakes), and the
  human may approve -- but see `landtransport.phase`: for
  `:dispatch/authorize`/`:reconciliation/publish` (a real dispatch
  authorization or reconciliation-record publication) NO phase ever
  allows auto-commit either. Two independent layers agree that
  actuation is always a human call.

    1. Spec-basis                    -- did the jurisdiction proposal
                                         cite an OFFICIAL source
                                         (`landtransport.facts`), or
                                         invent one?
    2. Evidence incomplete           -- for `:dispatch/authorize`/
                                         `:reconciliation/publish`, has
                                         the jurisdiction actually been
                                         assessed with a full safety-
                                         scope evidence checklist on
                                         file?
    3. Dispatch precondition unmet   -- for `:dispatch/authorize`,
                                         INDEPENDENTLY verify the
                                         dispatch-kind-specific
                                         precondition: a recovery job
                                         needs a completed vehicle-
                                         condition check; a terminal-
                                         slot operation needs verified
                                         evidence; a toll-lane dispatch
                                         has no extra precondition
                                         beyond safety scope (already
                                         covered by check 2).
    4. Recovery capacity exceeded    -- for `:dispatch/authorize` when
                                         `:dispatch-kind` is
                                         `:recovery-job`, INDEPENDENTLY
                                         verify the recovered vehicle's
                                         weight fits the tow vehicle's
                                         rated capacity via
                                         `landtransport.registry/
                                         recovery-capacity-exceeded?`
                                         (the fabrication measured-
                                         value-vs-rated-limit
                                         discipline) -- a real, well-
                                         known gross-vehicle-weight-vs-
                                         tow-capacity hazard.
    5. Already dispatched            -- double-actuation guard on
                                         `:dispatch/authorize`.
    6. Already reconciled            -- double-actuation guard on
                                         `:reconciliation/publish`.
    7. Confidence floor / actuation
       gate                          -- LLM confidence below threshold,
                                         OR the op is
                                         `:dispatch/authorize`/
                                         `:reconciliation/publish`
                                         (REAL acts) -> escalate."
  (:require [landtransport.facts :as facts]
            [landtransport.registry :as registry]
            [landtransport.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Authorizing a toll-lane change, a recovery job, or a terminal-slot
  operation dispatch, and publishing a multi-carrier reconciliation
  record for that dispatch, are the two real-world actuation events
  this actor performs -- a two-member set, matching every sibling's own
  dual-actuation shape."
  #{:dispatch/authorize :reconciliation/publish})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:safety-scope/verify` (or `:dispatch/authorize`/
  `:reconciliation/publish`) proposal with no spec-basis citation is a
  HARD violation -- never invent a jurisdiction's toll/terminal safety-
  scope requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:safety-scope/verify :dispatch/authorize :reconciliation/publish} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:dispatch/authorize`/`:reconciliation/publish`, the
  jurisdiction's required safety-scope evidence (toll plaza / bus
  terminal safety-scope registration, vehicle-recovery operator
  licensing record, multi-carrier terminal-slot booking evidence) must
  actually be satisfied -- do not trust the advisor's self-reported
  confidence alone."
  [{:keys [op subject]} st]
  (when (contains? #{:dispatch/authorize :reconciliation/publish} op)
    (let [w (store/land-dispatch st subject)
          assessment (store/assessment-of st subject)]
      (when-not (and assessment
                     (facts/required-evidence-satisfied?
                      (:jurisdiction w) (:checklist assessment)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(安全スコープ登録/回収業者ライセンス記録/複数事業者ターミナル予約証跡等)が充足していない状態での提案"}]))))

(defn- dispatch-precondition-violations
  "For `:dispatch/authorize` ONLY: the dispatch-kind-specific
  precondition. A recovery job without a completed vehicle-condition
  check, or a terminal-slot operation without verified evidence, is a
  HARD violation. A toll-lane dispatch has no extra precondition beyond
  safety scope (already covered by `evidence-incomplete-violations`).
  An unrecognized/missing `:dispatch-kind` is conservatively treated as
  a violation -- missing-data-is-unsafe, the same discipline every
  sibling's registry/governor check applies to a missing measured
  value."
  [{:keys [op subject]} st]
  (when (= op :dispatch/authorize)
    (let [w (store/land-dispatch st subject)]
      (case (:dispatch-kind w)
        :recovery-job
        (when (not (true? (:vehicle-condition-checked? w)))
          [{:rule :dispatch-precondition-unmet
            :detail (str subject " は回収業務の車両状態確認(vehicle-condition-check)が未完了 -- 派遣認可提案は進められない")}])

        :terminal-slot
        (when (not (true? (:terminal-slot-evidence-verified? w)))
          [{:rule :dispatch-precondition-unmet
            :detail (str subject " はターミナル枠運用の検証済み証跡が無い -- 派遣認可提案は進められない")}])

        :toll-lane
        nil

        [{:rule :dispatch-precondition-unmet
          :detail (str subject " は不明な dispatch-kind (" (pr-str (:dispatch-kind w)) ") -- 派遣認可提案は進められない")}]))))

(defn- recovery-capacity-exceeded-violations
  "For `:dispatch/authorize` ONLY, ONLY when `:dispatch-kind` is
  `:recovery-job`, INDEPENDENTLY verify the recovered vehicle's weight
  fits the tow vehicle's rated capacity via
  `landtransport.registry/recovery-capacity-exceeded?` (the fabrication
  measured-value-vs-rated-limit discipline) -- a real, well-known
  gross-vehicle-weight-vs-tow-capacity hazard."
  [{:keys [op subject]} st]
  (when (= op :dispatch/authorize)
    (let [w (store/land-dispatch st subject)]
      (when (= :recovery-job (:dispatch-kind w))
        (when (registry/recovery-capacity-exceeded?
               (:tow-vehicle-max-capacity-kg w)
               (:recovered-vehicle-weight-kg w))
          [{:rule :recovery-capacity-exceeded
            :detail (str subject " は被牽引車両重量(" (:recovered-vehicle-weight-kg w)
                        " kg)が牽引車両の定格容量(" (:tow-vehicle-max-capacity-kg w) " kg)を超過 -- "
                        "牽引容量超過リスクのため派遣認可提案は進められない")}])))))

(defn- already-dispatched-violations
  "For `:dispatch/authorize`, refuses to authorize a dispatch for the
  SAME record twice, off a dedicated `:dispatched?` fact (never a
  `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :dispatch/authorize)
    (when (store/land-dispatch-already-dispatched? st subject)
      [{:rule :already-dispatched
        :detail (str subject " は既に派遣認可済み")}])))

(defn- already-reconciled-violations
  "For `:reconciliation/publish`, refuses to publish a reconciliation
  record for the SAME record twice, off a dedicated
  `:reconciliation-published?` fact (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :reconciliation/publish)
    (when (store/land-dispatch-already-reconciled? st subject)
      [{:rule :already-reconciled
        :detail (str subject " は既に精算記録公開済み")}])))

(defn check
  "Censors a Land Transport Support Advisor proposal against the
  governor rules. Returns {:ok? bool :violations [..] :confidence c
  :escalate? bool :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (dispatch-precondition-violations request st)
                           (recovery-capacity-exceeded-violations request st)
                           (already-dispatched-violations request st)
                           (already-reconciled-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
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
