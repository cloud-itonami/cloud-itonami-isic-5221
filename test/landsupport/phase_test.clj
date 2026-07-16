(ns landsupport.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:flag-structural-safety-concern` must NEVER be a member
  of any phase's `:auto` set, and no op in the closed allowlist that
  would itself finalize a structural-safety clearance exists at all
  (there is no such op)."
  (:require [clojure.test :refer [deftest is testing]]
            [landsupport.phase :as phase]))

(deftest flag-structural-safety-concern-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in future entries, auto-commits a structural-safety concern flag"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :flag-structural-safety-concern))
          (str "phase " n " must not auto-commit :flag-structural-safety-concern")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-set-excludes-the-always-escalate-op
  (is (not (contains? (:auto (get phase/phases 3)) :flag-structural-safety-concern))))

(deftest phase-3-auto-set-is-exactly-the-two-low-risk-ops
  (testing "log-facility-record and coordinate-supply-order (governor cost-gated) are the only phase-3 auto-eligible ops"
    (is (= #{:log-facility-record :coordinate-supply-order} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :log-facility-record} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :flag-structural-safety-concern} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :schedule-facility-maintenance} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :log-facility-record} :commit)))))

(deftest gate-auto-commits-a-clean-low-risk-write-at-phase-3
  (is (= :commit (:disposition (phase/gate 3 {:op :log-facility-record} :commit))))
  (is (= :commit (:disposition (phase/gate 3 {:op :coordinate-supply-order} :commit)))))

(deftest every-op-is-a-write-op
  (testing "the closed allowlist and phase/write-ops agree on scope"
    (is (= #{:log-facility-record :schedule-facility-maintenance
             :flag-structural-safety-concern :coordinate-supply-order}
           phase/write-ops))))
