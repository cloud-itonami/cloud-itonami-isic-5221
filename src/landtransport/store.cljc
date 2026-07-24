(ns landtransport.store
  "SSoT for the land-transport-support operations actor, behind a
  `Store` protocol so the backend is a swap, not a rewrite -- the same
  seam every prior `cloud-itonami-isic-*` actor in this fleet uses.

  R0 ships `MemStore` only (atom of EDN, the deterministic default for
  dev/tests/demo, no deps) -- the SAME seam `terminal.store`
  (cloud-itonami-isic-5210) also uses for its own `MemStore`; a
  `DatomicStore` (`langchain.db`-backed) is the natural next seam
  (several sibling actors in this fleet ship MemStore-only at R0 for
  the same reason: proving the governed-actor contract does not require
  a second backend on day one) and can be added later against the SAME
  protocol without touching the actor, the governor or the audit
  ledger.

  Unlike `terminal.store`'s sequential `commit` then `transfer` shape,
  this vertical's `dispatch-authorize` and `reconciliation-publish`
  actuation events ALSO apply SEQUENTIALLY to the SAME `land-dispatch`
  record -- a dispatch is authorized first (a toll-lane change, a
  recovery job, or a terminal-slot operation is cleared to run), a
  reconciliation record is published later (the multi-carrier booking/
  billing record for that dispatch), on the same record. Dedicated
  double-actuation-guard booleans (`:dispatched?`/
  `:reconciliation-published?`, never a `:status` value) mirror every
  prior governor's guards.

  The ledger stays append-only: 'which record had a dispatch authorized
  outside its verified safety scope, which recovery job was dispatched
  without a completed vehicle-condition check, which terminal-slot
  operation was dispatched without verified evidence, which record had
  its recovery job exceed the tow vehicle's rated capacity, which
  record had a dispatch authorized, which record had a reconciliation
  published, on what jurisdictional basis, approved by whom' is always
  a query over an immutable log -- the audit trail a regulator, a
  carrier, or an operator trusting a land-transport-support actor
  needs, and the evidence an operator needs if a dispatch or a
  reconciliation record is later disputed."
  (:require [landtransport.registry :as registry]))

(defprotocol Store
  (land-dispatch [s id])
  (all-land-dispatches [s])
  (assessment-of [s land-dispatch-id] "committed safety-scope-verification assessment (checklist), or nil")
  (ledger [s])
  (act1-history [s] "the append-only dispatch-authorization history (landtransport.registry drafts)")
  (act2-history [s] "the append-only reconciliation-publish history (landtransport.registry drafts)")
  (next-authorization-sequence [s jurisdiction] "next authorization-number sequence for a jurisdiction")
  (next-reconciliation-sequence [s jurisdiction] "next reconciliation-number sequence for a jurisdiction")
  (land-dispatch-already-dispatched? [s land-dispatch-id] "has this record already had a dispatch authorized?")
  (land-dispatch-already-reconciled? [s land-dispatch-id] "has this record already had a reconciliation published?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-land-dispatches [s land-dispatches] "replace/seed the record directory (map id->land-dispatch)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained land-dispatch set covering both actuation
  lifecycles (dispatch-authorize, reconciliation-publish) plus the
  governor's own HARD checks, so the actor + tests run offline. Each
  dedicated failure-mode record isolates exactly ONE failure mode (the
  rest stay clean) following the 'exercise the failure mode directly,
  never only via a happy-path actuation' discipline every sibling
  governor's demo data establishes."
  []
  {:land-dispatches
   {"dispatch-1" {:id "dispatch-1" :plaza-or-terminal-id "TOLL-42"
                  :dispatch-kind :toll-lane
                  :safety-scope-verified? true
                  :vehicle-condition-checked? true
                  :terminal-slot-evidence-verified? true
                  :tow-vehicle-max-capacity-kg 5000
                  :recovered-vehicle-weight-kg 3200
                  :carrier-count 3
                  :dispatched? false :reconciliation-published? false
                  :jurisdiction "JPN" :status :intake}
    "dispatch-2" {:id "dispatch-2" :plaza-or-terminal-id "TOLL-99"
                  :dispatch-kind :toll-lane
                  :safety-scope-verified? true
                  :vehicle-condition-checked? true
                  :terminal-slot-evidence-verified? true
                  :tow-vehicle-max-capacity-kg 5000
                  :recovered-vehicle-weight-kg 3200
                  :carrier-count 2
                  :dispatched? false :reconciliation-published? false
                  :jurisdiction "ATL" :status :intake}
    "dispatch-3" {:id "dispatch-3" :plaza-or-terminal-id "DEPOT-7"
                  :dispatch-kind :recovery-job
                  :safety-scope-verified? true
                  :vehicle-condition-checked? false
                  :terminal-slot-evidence-verified? true
                  :tow-vehicle-max-capacity-kg 5000
                  :recovered-vehicle-weight-kg 3000
                  :carrier-count 1
                  :dispatched? false :reconciliation-published? false
                  :jurisdiction "JPN" :status :intake}
    "dispatch-4" {:id "dispatch-4" :plaza-or-terminal-id "DEPOT-8"
                  :dispatch-kind :recovery-job
                  :safety-scope-verified? true
                  :vehicle-condition-checked? true
                  :terminal-slot-evidence-verified? true
                  :tow-vehicle-max-capacity-kg 5000
                  :recovered-vehicle-weight-kg 8000
                  :carrier-count 1
                  :dispatched? false :reconciliation-published? false
                  :jurisdiction "JPN" :status :intake}
    "dispatch-5" {:id "dispatch-5" :plaza-or-terminal-id "TERMINAL-3"
                  :dispatch-kind :terminal-slot
                  :safety-scope-verified? true
                  :vehicle-condition-checked? true
                  :terminal-slot-evidence-verified? false
                  :tow-vehicle-max-capacity-kg 5000
                  :recovered-vehicle-weight-kg 3200
                  :carrier-count 4
                  :dispatched? false :reconciliation-published? false
                  :jurisdiction "JPN" :status :intake}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- authorize-dispatch!*
  "Backend-agnostic `:dispatch/mark-authorized` -- looks up the
  land-dispatch via the protocol and drafts the dispatch-authorization
  record, and returns {:result .. :dispatch-patch ..} for the caller to
  persist."
  [s land-dispatch-id]
  (let [w (land-dispatch s land-dispatch-id)
        seq-n (next-authorization-sequence s (:jurisdiction w))
        result (registry/register-authorization-record land-dispatch-id (:jurisdiction w) seq-n)]
    {:result result
     :dispatch-patch {:dispatched? true
                      :authorization-number (get result "authorization_number")}}))

(defn- publish-reconciliation!*
  "Backend-agnostic `:dispatch/mark-reconciled` -- looks up the
  land-dispatch via the protocol and drafts the reconciliation-publish
  record, and returns {:result .. :dispatch-patch ..} for the caller to
  persist."
  [s land-dispatch-id]
  (let [w (land-dispatch s land-dispatch-id)
        seq-n (next-reconciliation-sequence s (:jurisdiction w))
        result (registry/register-reconciliation-record land-dispatch-id (:jurisdiction w) seq-n)]
    {:result result
     :dispatch-patch {:reconciliation-published? true
                      :reconciliation-number (get result "reconciliation_number")}}))

;; ----------------------------- MemStore (default, and only backend at R0) -----------------------------

(defrecord MemStore [a]
  Store
  (land-dispatch [_ id] (get-in @a [:land-dispatches id]))
  (all-land-dispatches [_] (sort-by :id (vals (:land-dispatches @a))))
  (assessment-of [_ land-dispatch-id] (get-in @a [:assessments land-dispatch-id]))
  (ledger [_] (:ledger @a))
  (act1-history [_] (:authorizations @a))
  (act2-history [_] (:reconciliations @a))
  (next-authorization-sequence [_ jurisdiction] (get-in @a [:authorization-sequences jurisdiction] 0))
  (next-reconciliation-sequence [_ jurisdiction] (get-in @a [:reconciliation-sequences jurisdiction] 0))
  (land-dispatch-already-dispatched? [_ land-dispatch-id] (boolean (get-in @a [:land-dispatches land-dispatch-id :dispatched?])))
  (land-dispatch-already-reconciled? [_ land-dispatch-id] (boolean (get-in @a [:land-dispatches land-dispatch-id :reconciliation-published?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :dispatch/upsert
      (swap! a update-in [:land-dispatches (:id value)] merge value)

      :safety-scope-assessment/set
      (swap! a assoc-in [:assessments (first path)] payload)

      :dispatch/mark-authorized
      (let [land-dispatch-id (first path)
            {:keys [result dispatch-patch]} (authorize-dispatch!* s land-dispatch-id)
            jurisdiction (:jurisdiction (land-dispatch s land-dispatch-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:authorization-sequences jurisdiction] (fnil inc 0))
                       (update-in [:land-dispatches land-dispatch-id] merge dispatch-patch)
                       (update :authorizations registry/append result))))
        result)

      :dispatch/mark-reconciled
      (let [land-dispatch-id (first path)
            {:keys [result dispatch-patch]} (publish-reconciliation!* s land-dispatch-id)
            jurisdiction (:jurisdiction (land-dispatch s land-dispatch-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:reconciliation-sequences jurisdiction] (fnil inc 0))
                       (update-in [:land-dispatches land-dispatch-id] merge dispatch-patch)
                       (update :reconciliations registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-land-dispatches [s land-dispatches] (when (seq land-dispatches) (swap! a assoc :land-dispatches land-dispatches)) s))

(defn seed-db
  "A MemStore seeded with the demo land-dispatch set. The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :assessments {}
                           :ledger [] :authorization-sequences {} :authorizations []
                           :reconciliation-sequences {} :reconciliations []))))
