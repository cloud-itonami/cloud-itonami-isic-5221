(ns landsupport.operation-test
  "The governor+phase contract as executable end-to-end tests, run
  through the actual `landsupport.operation` langgraph-clj graph (not
  the unit-level `governor_test.clj`/`phase_test.clj`). The single
  invariant under test: the Land Transport Support Advisor never
  commits a proposal the Land Transport Support Governor would reject,
  `:flag-structural-safety-concern` NEVER auto-commits at any phase,
  `:log-facility-record`/low-cost `:coordinate-supply-order` MAY auto-
  commit when clean, and every decision (commit OR hold) leaves
  exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [landsupport.advisor :as advisor]
            [landsupport.store :as store]
            [landsupport.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :land-transport-support-operator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(deftest clean-facility-record-log-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :log-facility-record :subject "facility-1"
                   :entry {:kind :toll-transaction :amount 350}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 1 (count (store/facility-record-log db "facility-1"))))
    (is (= 1 (count (store/ledger db))))))

(deftest schedule-maintenance-always-needs-approval
  (testing "scheduling is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :schedule-facility-maintenance :subject "facility-1"
                                   :window {:start "2026-08-01" :end "2026-08-03"}} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/maintenance-schedule-log db "facility-1"))))))))

(deftest unregistered-facility-is-held-never-reaches-human
  (testing "an unregistered facility -> HOLD, no interrupt, regardless of op"
    (let [[db actor] (fresh)
          res (exec-op actor "t3" {:op :log-facility-record :subject "facility-3"
                                   :entry {:kind :parking-occupancy :count 12}} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:facility-unverified} (-> (store/ledger db) first :basis)))
      (is (empty? (store/facility-record-log db "facility-3"))))))

(deftest unverified-facility-is-held-never-reaches-human
  (let [[db actor] (fresh)
        res (exec-op actor "t3b" {:op :schedule-facility-maintenance :subject "facility-5"
                                  :window {:start "2026-09-01"}} operator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:facility-unverified} (-> (store/ledger db) first :basis)))))

(deftest out-of-allowlist-op-is-held-never-reaches-human
  (let [[db actor] (fresh)
        res (exec-op actor "t3c" {:op :dispatch-toll-lane :subject "facility-1"} operator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:op-not-allowed} (-> (store/ledger db) first :basis)))))

(deftest structural-safety-clearance-finalization-attempt-is-held-and-unoverridable
  (testing "a proposal that words its way into finalizing a structural-safety clearance -> HOLD, never reaches request-approval, regardless of which op it claims to be"
    (let [db (store/seed-db)
          bad-advisor (reify advisor/Advisor
                        (-advise [_ _st _req]
                          {:summary "cert" :rationale "Finalize the structural-safety clearance for this bridge."
                           :cites [] :effect :propose :op :log-facility-record :value {} :stake nil :confidence 0.99}))
          actor (op/build db {:advisor bad-advisor})
          res (exec-op actor "t4" {:op :log-facility-record :subject "facility-1"
                                   :entry {:kind :toll-transaction}} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)) "settles immediately, no human ever sees a clearance-finalization attempt")
      (is (some #{:structural-safety-clearance-finalization} (-> (store/ledger db) first :basis)))
      (is (empty? (store/facility-record-log db "facility-1"))))))

(deftest flag-structural-safety-concern-always-escalates-then-human-decides
  (testing "a clean, fully-verified facility still ALWAYS interrupts for human review -- flag-structural-safety-concern is never auto"
    (let [[db actor] (fresh)
          r1 (exec-op actor "t5" {:op :flag-structural-safety-concern :subject "facility-1"
                                  :observation "hairline crack observed"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human structural-safety review even when governor-clean")
      (testing "approve -> commit, concern logged, facility's unresolved flag set"
        (let [r2 (approve! actor "t5")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= 1 (count (store/concern-log db "facility-1"))))
          (is (true? (:structural-safety-concern-unresolved? (store/facility db "facility-1")))))))))

(deftest low-cost-supply-order-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t6" {:op :coordinate-supply-order :subject "facility-1"
                                 :materials ["barrier tape"] :cost-estimate 1200} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 1 (count (store/supply-order-log db "facility-1"))))))

(deftest high-cost-supply-order-always-escalates-then-human-decides
  (let [[db actor] (fresh)
        r1 (exec-op actor "t7" {:op :coordinate-supply-order :subject "facility-2"
                                :materials ["gantry sensor array"] :cost-estimate 80000} operator)]
    (is (= :interrupted (:status r1)))
    (let [r2 (approve! actor "t7")]
      (is (= :commit (get-in r2 [:state :disposition])))
      (is (= 1 (count (store/supply-order-log db "facility-2")))))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :log-facility-record :subject "facility-1"
                          :entry {:kind :toll-transaction}} operator)
      (exec-op actor "b" {:op :log-facility-record :subject "facility-3"
                          :entry {:kind :toll-transaction}} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))

(deftest approval-rejected-is-a-hold-not-a-commit
  (let [[db actor] (fresh)
        _ (exec-op actor "t8" {:op :schedule-facility-maintenance :subject "facility-1"
                               :window {:start "2026-08-01"}} operator)
        r (g/run* actor {:approval {:status :rejected :by "op-1"}} {:thread-id "t8" :resume? true})]
    (is (= :hold (get-in r [:state :disposition])))
    (is (empty? (store/maintenance-schedule-log db "facility-1")))))
