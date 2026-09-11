(ns landtransport.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean land-dispatch
  record through intake -> safety-scope verification -> dispatch
  authorization (escalate/approve/commit) -> reconciliation publish
  (escalate/approve/commit), then shows HARD-hold scenarios: a
  jurisdiction with no spec-basis, a recovery job without a completed
  vehicle-condition check, a recovery job whose recovered vehicle
  exceeds the tow vehicle's rated capacity, a terminal-slot operation
  without verified evidence, a double dispatch-authorize, and a double
  reconciliation-publish.

  Like every sibling actor's checks, this actor's land-transport-
  support checks (`dispatch-precondition-violations`,
  `recovery-capacity-exceeded-violations`) are evaluated directly at
  `:dispatch/authorize` time rather than via a separate screening op --
  a real dispatch-authorization decision validates the safety-scope
  evidence, the dispatch-kind-specific precondition and the recovery-
  capacity range at the point of the act itself, not as a discrete
  pre-screening ceremony. Each check is still exercised directly and
  independently below, one record per HARD-hold scenario, following
  the SAME 'exercise the failure mode directly, never only via a
  happy-path actuation' discipline every sibling since establishes."
  (:require [langgraph.graph :as g]
            [landtransport.store :as store]
            [landtransport.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :depot-superintendent :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== safety-scope/intake dispatch-1 (JPN, clean) ==")
    (println (exec-op actor "t1" {:op :safety-scope/intake :subject "dispatch-1"
                                  :patch {:id "dispatch-1" :plaza-or-terminal-id "TOLL-42"}} operator))

    (println "== safety-scope/verify dispatch-1 (escalates -- human approves) ==")
    (println (exec-op actor "t2" {:op :safety-scope/verify :subject "dispatch-1"} operator))
    (println (approve! actor "t2"))

    (println "== dispatch/authorize dispatch-1 (always escalates -- :dispatch/authorize) ==")
    (let [r (exec-op actor "t3" {:op :dispatch/authorize :subject "dispatch-1"} operator)]
      (println r)
      (println "-- human operator approves --")
      (println (approve! actor "t3")))

    (println "== reconciliation/publish dispatch-1 (always escalates -- :reconciliation/publish) ==")
    (let [r (exec-op actor "t4" {:op :reconciliation/publish :subject "dispatch-1"} operator)]
      (println r)
      (println "-- human operator approves --")
      (println (approve! actor "t4")))

    (println "== safety-scope/verify dispatch-2 (no spec-basis -> HARD hold) ==")
    (println (exec-op actor "t5" {:op :safety-scope/verify :subject "dispatch-2"} operator))

    (println "== safety-scope/verify dispatch-3 (escalates -- human approves; sets up the precondition test) ==")
    (println (exec-op actor "t6" {:op :safety-scope/verify :subject "dispatch-3"} operator))
    (println (approve! actor "t6"))

    (println "== dispatch/authorize dispatch-3 (recovery job, vehicle-condition not checked -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :dispatch/authorize :subject "dispatch-3"} operator))

    (println "== safety-scope/verify dispatch-4 (escalates -- human approves; sets up the capacity test) ==")
    (println (exec-op actor "t8" {:op :safety-scope/verify :subject "dispatch-4"} operator))
    (println (approve! actor "t8"))

    (println "== dispatch/authorize dispatch-4 (recovered vehicle exceeds tow capacity -> HARD hold) ==")
    (println (exec-op actor "t9" {:op :dispatch/authorize :subject "dispatch-4"} operator))

    (println "== safety-scope/verify dispatch-5 (escalates -- human approves; sets up the terminal-slot test) ==")
    (println (exec-op actor "t10" {:op :safety-scope/verify :subject "dispatch-5"} operator))
    (println (approve! actor "t10"))

    (println "== dispatch/authorize dispatch-5 (terminal-slot evidence unverified -> HARD hold) ==")
    (println (exec-op actor "t11" {:op :dispatch/authorize :subject "dispatch-5"} operator))

    (println "== dispatch/authorize dispatch-1 AGAIN (double-dispatch -> HARD hold) ==")
    (println (exec-op actor "t12" {:op :dispatch/authorize :subject "dispatch-1"} operator))

    (println "== reconciliation/publish dispatch-1 AGAIN (double-reconcile -> HARD hold) ==")
    (println (exec-op actor "t13" {:op :reconciliation/publish :subject "dispatch-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft dispatch-authorization records ==")
    (doseq [r (store/act1-history db)] (println r))

    (println "== draft reconciliation-publish records ==")
    (doseq [r (store/act2-history db)] (println r))))
