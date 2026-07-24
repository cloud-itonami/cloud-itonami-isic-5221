(ns landtransport.registry
  "Pure-function dispatch-authorization + reconciliation-publish record
  construction -- an append-only land-transport-support book-of-record
  draft -- AND the pure tow-vehicle-recovery-capacity range-check
  function the Land Transport Support Governor calls to re-verify a
  recovery job's own physical ground truth before any dispatch
  authorization.

  Like `terminal.registry` (cloud-itonami-isic-5210), this vertical has
  NO pre-existing capability library to wrap -- there is no
  'kotoba-lang/land-transport-support' to call. So this namespace is
  self-contained: the recovery-capacity check (recovered vehicle weight
  vs the tow vehicle's rated capacity) is a pure function defined HERE,
  not delegated. The actor layer adds the governed proposal/approval
  loop on top; the governor calls this same pure function to
  INDEPENDENTLY re-verify the recovery job's own recorded values before
  any real-world dispatch authorization, rather than trusting the
  advisor's self-reported confidence.

  Like every sibling actor's registry, there is no single international
  reference-number standard for a dispatch-authorization or
  reconciliation-publish record -- every operator/jurisdiction assigns
  its own reference format. This namespace does NOT invent one beyond a
  jurisdiction-scoped sequence number; it validates the record's
  required fields, the same honest, non-fabricating discipline
  `landtransport.facts` uses.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real toll-gantry / SCADA / vehicle-recovery-dispatch
  system. It builds the RECORD an operator would keep, not the act of
  authorizing a dispatch or publishing a reconciliation record itself
  (that is `landtransport.operation`'s `:dispatch/authorize`/
  `:reconciliation/publish`, always human-gated -- see README
  `Actuation`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the operator's act, not this actor's. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

;; ----------------------------- tow-vehicle recovery-capacity range check (pure) -----------------------------
;;
;; The Land Transport Support Governor calls this to INDEPENDENTLY
;; re-verify a recovery job's own recorded physical values before
;; authorizing a dispatch. Returns true when the recovered vehicle's
;; weight would exceed the tow vehicle's rated capacity -- the
;; conservative land-transport-safety choice, matching the measured-
;; value-vs-rated-limit discipline of the fabrication siblings (and
;; `terminal.registry/overfill-risk?`'s own reapplication of it): a
;; recovery job that cannot be certified to fit within the tow
;; vehicle's rated capacity is treated as a violation, not as
;; 'unknown therefore ok'. Missing data -> violation (cannot verify
;; safe to dispatch -- an overloaded tow vehicle is a real,
;; well-documented gross-vehicle-weight-vs-tow-capacity hazard).

(defn recovery-capacity-exceeded?
  "Recovered vehicle weight vs the tow vehicle's rated max capacity --
  the fabrication 'measured value exceeds rated limit' pattern, applied
  to vehicle-recovery towing safety. Missing any value -> unsafe
  (cannot verify the recovery job fits before dispatching the tow
  vehicle)."
  [tow-vehicle-max-capacity-kg recovered-vehicle-weight-kg]
  (cond
    (or (nil? tow-vehicle-max-capacity-kg) (nil? recovered-vehicle-weight-kg)) true
    (> recovered-vehicle-weight-kg tow-vehicle-max-capacity-kg) true
    :else false))

;; ----------------------------- record construction -----------------------------

(defn register-authorization-record
  "Validate + construct the DISPATCH-AUTHORIZATION registration DRAFT --
  the operator's own legal act of authorizing a toll-lane dispatch,
  recovery-job dispatch or terminal-slot operation dispatch. Pure
  function -- does not touch any real toll-gantry / SCADA / vehicle-
  recovery-dispatch system; it builds the RECORD an operator would
  keep. `landtransport.governor` independently re-verifies the safety-
  scope evidence, the dispatch-kind-specific precondition (a completed
  vehicle-condition check for a recovery job, verified evidence for a
  terminal-slot operation), the recovery-capacity range (recovered
  vehicle weight vs tow-vehicle rated capacity), and blocks a double-
  dispatch of the same record, before this is ever allowed to commit."
  [land-dispatch-id jurisdiction sequence]
  (when-not (and land-dispatch-id (not= land-dispatch-id ""))
    (throw (ex-info "dispatch-authorize: land_dispatch_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "dispatch-authorize: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "dispatch-authorize: sequence must be >= 0" {})))
  (let [authorization-number (str (str/upper-case jurisdiction) "-DISPATCH-" (zero-pad sequence 6))
        record {"record_id" authorization-number
                "kind" "dispatch-authorization-draft"
                "land_dispatch_id" land-dispatch-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "authorization_number" authorization-number
     "certificate" (unsigned-certificate "DispatchAuthorization" authorization-number authorization-number)}))

(defn register-reconciliation-record
  "Validate + construct the RECONCILIATION-PUBLISH registration DRAFT --
  the operator's own legal act of publishing a multi-carrier terminal-
  slot booking / dispatch reconciliation record. Pure function -- does
  not touch any real billing / reconciliation ledger system; it builds
  the RECORD an operator would keep. `landtransport.governor`
  independently re-verifies the safety-scope evidence and blocks a
  double-publish of the same record, before this is ever allowed to
  commit."
  [land-dispatch-id jurisdiction sequence]
  (when-not (and land-dispatch-id (not= land-dispatch-id ""))
    (throw (ex-info "reconciliation-publish: land_dispatch_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "reconciliation-publish: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "reconciliation-publish: sequence must be >= 0" {})))
  (let [reconciliation-number (str (str/upper-case jurisdiction) "-RECON-" (zero-pad sequence 6))
        record {"record_id" reconciliation-number
                "kind" "reconciliation-publish-draft"
                "land_dispatch_id" land-dispatch-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "reconciliation_number" reconciliation-number
     "certificate" (unsigned-certificate "ReconciliationPublish" reconciliation-number reconciliation-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
