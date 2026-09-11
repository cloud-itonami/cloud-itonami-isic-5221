(ns landtransport.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:dispatch/authorize`/`:reconciliation/publish` must
  NEVER be a member of any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [landtransport.phase :as phase]))

(deftest dispatch-authorize-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in any future entry, auto-commits a real dispatch authorization"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :dispatch/authorize))
          (str "phase " n " must not auto-commit :dispatch/authorize")))))

(deftest reconciliation-publish-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in any future entry, auto-commits a real reconciliation publication"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :reconciliation/publish))
          (str "phase " n " must not auto-commit :reconciliation/publish")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-dispatch-risk-ops
  (testing ":safety-scope/intake carries no dispatch risk -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:safety-scope/intake} (:auto (get phase/phases 3))))))

(deftest safety-scope-verify-never-auto-at-any-phase
  (testing "safety-scope verification always needs human approval, even at phase 3, matching every sibling's own 'verify' op"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :safety-scope/verify))
          (str "phase " n " must not auto-commit :safety-scope/verify")))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :safety-scope/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :dispatch/authorize} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :reconciliation/publish} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :safety-scope/intake} :commit)))))
