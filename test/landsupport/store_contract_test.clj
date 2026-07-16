(ns landsupport.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a
  configuration change, not a rewrite -- see
  `cloud-itonami-isic-3512`'s `energy.store-contract-test` for the
  same pattern on the sibling actor."
  (:require [clojure.test :refer [deftest is testing]]
            [landsupport.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "Sakura Tollway Plaza 3" (:facility-name (store/facility s "facility-1"))))
      (is (= :toll-road (:facility-type (store/facility s "facility-1"))))
      (is (= "JPN" (:jurisdiction (store/facility s "facility-1"))))
      (is (true? (:registered? (store/facility s "facility-1"))))
      (is (true? (:verified? (store/facility s "facility-1"))))
      (is (false? (:structural-safety-concern-unresolved? (store/facility s "facility-1"))))
      (is (false? (:registered? (store/facility s "facility-3"))))
      (is (false? (:verified? (store/facility s "facility-5"))))
      (is (true? (:structural-safety-concern-unresolved? (store/facility s "facility-4"))))
      (is (= ["facility-1" "facility-2" "facility-3" "facility-4" "facility-5"]
             (mapv :id (store/all-facilities s))))
      (is (= [] (store/facility-record-log s "facility-1")))
      (is (= [] (store/maintenance-schedule-log s "facility-1")))
      (is (= [] (store/concern-log s "facility-1")))
      (is (= [] (store/supply-order-log s "facility-1")))
      (is (= [] (store/ledger s))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "facility-record log entries append"
        (store/commit-record! s {:op :log-facility-record :path ["facility-1"]
                                 :value {:kind :facility-usage-log :entry {:amount 350}}})
        (is (= 1 (count (store/facility-record-log s "facility-1"))))
        (is (= {:kind :facility-usage-log :entry {:amount 350}}
               (first (store/facility-record-log s "facility-1")))))
      (testing "maintenance-schedule proposals append"
        (store/commit-record! s {:op :schedule-facility-maintenance :path ["facility-1"]
                                 :value {:kind :maintenance-schedule-proposal
                                        :window {:start "2026-08-01"}}})
        (is (= 1 (count (store/maintenance-schedule-log s "facility-1")))))
      (testing "flagging a structural-safety concern appends AND sets the facility's unresolved flag"
        (is (false? (:structural-safety-concern-unresolved? (store/facility s "facility-1"))))
        (store/commit-record! s {:op :flag-structural-safety-concern :path ["facility-1"]
                                 :value {:kind :structural-safety-concern-flag :observation "crack"}})
        (is (= 1 (count (store/concern-log s "facility-1"))))
        (is (true? (:structural-safety-concern-unresolved? (store/facility s "facility-1")))
            "commit-record! never resolves a concern -- it only ever sets unresolved? true"))
      (testing "supply-order proposals append"
        (store/commit-record! s {:op :coordinate-supply-order :path ["facility-2"]
                                 :value {:kind :supply-order-proposal :materials ["tape"] :cost-estimate 1200}})
        (is (= 1 (count (store/supply-order-log s "facility-2")))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/facility s "nope")))
    (is (= [] (store/all-facilities s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/facility-record-log s "nope")))
    (store/with-facilities s {"x" {:id "x" :facility-name "n" :facility-type :toll-road
                                   :registered? true :verified? true
                                   :structural-safety-concern-unresolved? false
                                   :jurisdiction "JPN"}})
    (is (= "n" (:facility-name (store/facility s "x"))))))
