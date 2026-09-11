(ns landtransport.store-contract-test
  "The Store contract as executable tests. R0 ships `MemStore` only
  (see `landtransport.store` docstring for why a second backend is a
  deferred, additive seam rather than a day-one requirement); this
  test proves MemStore itself satisfies the FULL protocol contract, the
  same discipline `terminal.store-contract-test`
  (cloud-itonami-isic-5210) applies across its own two backends. Adding
  a `DatomicStore` later is a matter of proving IT against this same
  contract, not rewriting it."
  (:require [clojure.test :refer [deftest is testing]]
            [landtransport.store :as store]))

(deftest read-parity
  (let [s (store/seed-db)]
    (is (= "JPN" (:jurisdiction (store/land-dispatch s "dispatch-1"))))
    (is (= "TOLL-42" (:plaza-or-terminal-id (store/land-dispatch s "dispatch-1"))))
    (is (= :toll-lane (:dispatch-kind (store/land-dispatch s "dispatch-1"))))
    (is (= "ATL" (:jurisdiction (store/land-dispatch s "dispatch-2"))))
    (is (false? (:vehicle-condition-checked? (store/land-dispatch s "dispatch-3"))) "dispatch-3 vehicle-condition not checked")
    (is (= 8000 (:recovered-vehicle-weight-kg (store/land-dispatch s "dispatch-4"))) "dispatch-4 exceeds tow capacity")
    (is (false? (:terminal-slot-evidence-verified? (store/land-dispatch s "dispatch-5"))) "dispatch-5 terminal-slot evidence unverified")
    (is (false? (:dispatched? (store/land-dispatch s "dispatch-1"))))
    (is (false? (:reconciliation-published? (store/land-dispatch s "dispatch-1"))))
    (is (= ["dispatch-1" "dispatch-2" "dispatch-3" "dispatch-4" "dispatch-5"]
           (mapv :id (store/all-land-dispatches s))))
    (is (nil? (store/assessment-of s "dispatch-1")))
    (is (= [] (store/ledger s)))
    (is (= [] (store/act1-history s)))
    (is (= [] (store/act2-history s)))
    (is (zero? (store/next-authorization-sequence s "JPN")))
    (is (zero? (store/next-reconciliation-sequence s "JPN")))
    (is (false? (store/land-dispatch-already-dispatched? s "dispatch-1")))
    (is (false? (store/land-dispatch-already-reconciled? s "dispatch-1")))))

(deftest write-and-ledger-parity
  (let [s (store/seed-db)]
    (testing "partial upsert merges, preserving untouched fields"
      (store/commit-record! s {:effect :dispatch/upsert
                               :value {:id "dispatch-1" :plaza-or-terminal-id "TOLL-999"}})
      (is (= "TOLL-999" (:plaza-or-terminal-id (store/land-dispatch s "dispatch-1"))))
      (is (= "JPN" (:jurisdiction (store/land-dispatch s "dispatch-1"))) "unrelated field preserved"))
    (testing "safety-scope-assessment payloads commit and read back"
      (store/commit-record! s {:effect :safety-scope-assessment/set :path ["dispatch-1"]
                               :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
      (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/assessment-of s "dispatch-1"))))
    (testing "dispatch authorization drafts a record and advances the authorization sequence"
      (store/commit-record! s {:effect :dispatch/mark-authorized :path ["dispatch-1"]})
      (is (= "JPN-DISPATCH-000000" (get (first (store/act1-history s)) "record_id")))
      (is (= "dispatch-authorization-draft" (get (first (store/act1-history s)) "kind")))
      (is (true? (:dispatched? (store/land-dispatch s "dispatch-1"))))
      (is (= 1 (count (store/act1-history s))))
      (is (= 1 (store/next-authorization-sequence s "JPN")))
      (is (true? (store/land-dispatch-already-dispatched? s "dispatch-1"))))
    (testing "reconciliation publish drafts a record and advances the reconciliation sequence"
      (store/commit-record! s {:effect :dispatch/mark-reconciled :path ["dispatch-1"]})
      (is (= "JPN-RECON-000000" (get (first (store/act2-history s)) "record_id")))
      (is (= "reconciliation-publish-draft" (get (first (store/act2-history s)) "kind")))
      (is (true? (:reconciliation-published? (store/land-dispatch s "dispatch-1"))))
      (is (= 1 (count (store/act2-history s))))
      (is (= 1 (store/next-reconciliation-sequence s "JPN")))
      (is (true? (store/land-dispatch-already-reconciled? s "dispatch-1"))))
    (testing "ledger is append-only and order-preserving"
      (store/append-ledger! s {:op :a :disposition :commit})
      (store/append-ledger! s {:op :b :disposition :hold})
      (is (= [:commit :hold] (mapv :disposition (store/ledger s)))))))

(deftest empty-store-is-usable
  (let [s (store/->MemStore (atom {:land-dispatches {} :assessments {} :ledger []
                                   :authorization-sequences {} :authorizations []
                                   :reconciliation-sequences {} :reconciliations []}))]
    (is (nil? (store/land-dispatch s "nope")))
    (is (= [] (store/all-land-dispatches s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/act1-history s)))
    (is (= [] (store/act2-history s)))
    (is (zero? (store/next-authorization-sequence s "JPN")))
    (is (zero? (store/next-reconciliation-sequence s "JPN")))
    (store/with-land-dispatches s {"x" {:id "x" :plaza-or-terminal-id "TOLL-X" :dispatch-kind :toll-lane
                                        :safety-scope-verified? true
                                        :vehicle-condition-checked? true
                                        :terminal-slot-evidence-verified? true
                                        :tow-vehicle-max-capacity-kg 5000
                                        :recovered-vehicle-weight-kg 3200
                                        :carrier-count 1
                                        :dispatched? false :reconciliation-published? false
                                        :jurisdiction "JPN" :status :intake}})
    (is (= "TOLL-X" (:plaza-or-terminal-id (store/land-dispatch s "x"))))))
