(ns landsupport.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean facility through
  intake-adjacent flows (facility-record logging [auto-commits at
  phase 3] -> maintenance-scheduling proposal [escalates -- human
  approves] -> low-cost supply-order coordination [auto-commits at
  phase 3] -> high-cost supply-order coordination [escalates -- human
  approves] -> structural-safety-concern flag [ALWAYS escalates --
  human approves]), then shows four HARD holds (an unregistered
  facility, an unverified facility, an out-of-allowlist op, and a
  proposal that tries to word its way into finalizing a structural-
  safety clearance) that never reach a human at all, and prints the
  audit ledger."
  (:require [langgraph.graph :as g]
            [landsupport.store :as store]
            [landsupport.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :land-transport-support-operator :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== log-facility-record facility-1 (registered+verified; auto-commits) ==")
    (println (exec! actor "t1" {:op :log-facility-record :subject "facility-1"
                                :entry {:kind :toll-transaction :amount 350}} operator))

    (println "== schedule-facility-maintenance facility-1 (escalates -- human approves) ==")
    (println (exec! actor "t2" {:op :schedule-facility-maintenance :subject "facility-1"
                                :window {:start "2026-08-01" :end "2026-08-03"}} operator))
    (println (approve! actor "t2"))

    (println "== coordinate-supply-order facility-1, low-cost (auto-commits at phase 3) ==")
    (println (exec! actor "t3" {:op :coordinate-supply-order :subject "facility-1"
                                :materials ["barrier tape" "signage"] :cost-estimate 1200} operator))

    (println "== coordinate-supply-order facility-2, high-cost (escalates -- human approves) ==")
    (let [r (exec! actor "t4" {:op :coordinate-supply-order :subject "facility-2"
                               :materials ["gantry sensor array"] :cost-estimate 80000} operator)]
      (println r)
      (println "-- human land-transport-support operator approves --")
      (println (approve! actor "t4")))

    (println "== flag-structural-safety-concern facility-1 (ALWAYS escalates -- human approves) ==")
    (let [r (exec! actor "t5" {:op :flag-structural-safety-concern :subject "facility-1"
                               :observation "hairline crack observed near expansion joint"} operator)]
      (println r)
      (println "-- human structural-safety engineer reviews and approves the FLAG (not a clearance) --")
      (println (approve! actor "t5")))

    (println "== log-facility-record facility-3 (unregistered -> HARD hold) ==")
    (println (exec! actor "t6" {:op :log-facility-record :subject "facility-3"
                                :entry {:kind :parking-occupancy :count 12}} operator))

    (println "== schedule-facility-maintenance facility-5 (registered but unverified -> HARD hold) ==")
    (println (exec! actor "t7" {:op :schedule-facility-maintenance :subject "facility-5"
                                :window {:start "2026-09-01" :end "2026-09-02"}} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))))
