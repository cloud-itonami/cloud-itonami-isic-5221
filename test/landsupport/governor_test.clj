(ns landsupport.governor-test
  "Direct unit tests of `landsupport.governor/check`'s four HARD checks
  and two SOFT escalation triggers, exercised against hand-built
  proposals (not the full graph -- see `operation_test.clj` for the
  end-to-end graph tests, and `scope_exclusion_test.clj` for the
  dedicated self-tripping-bug regression test required by this
  actor's build-out)."
  (:require [clojure.test :refer [deftest is testing]]
            [landsupport.governor :as governor]
            [landsupport.store :as store]))

(defn- clean-proposal [op value]
  {:summary "test" :rationale "test rationale" :cites [] :effect :propose
   :op op :value value :stake nil :confidence 0.9})

(deftest facility-not-found-is-hard-hold
  (let [st (store/seed-db)
        verdict (governor/check {:op :log-facility-record :subject "facility-does-not-exist"}
                                {} (clean-proposal :log-facility-record {}) st)]
    (is (true? (:hard? verdict)))
    (is (some #{:facility-unverified} (map :rule (:violations verdict))))))

(deftest unregistered-facility-is-hard-hold
  (let [st (store/seed-db)
        verdict (governor/check {:op :log-facility-record :subject "facility-3"}
                                {} (clean-proposal :log-facility-record {}) st)]
    (is (true? (:hard? verdict)))
    (is (some #{:facility-unverified} (map :rule (:violations verdict))))))

(deftest unverified-facility-is-hard-hold
  (let [st (store/seed-db)
        verdict (governor/check {:op :schedule-facility-maintenance :subject "facility-5"}
                                {} (clean-proposal :schedule-facility-maintenance {}) st)]
    (is (true? (:hard? verdict)))
    (is (some #{:facility-unverified} (map :rule (:violations verdict))))))

(deftest registered-and-verified-facility-passes-check-1
  (let [st (store/seed-db)
        verdict (governor/check {:op :log-facility-record :subject "facility-1"}
                                {} (clean-proposal :log-facility-record {}) st)]
    (is (not (some #{:facility-unverified} (map :rule (:violations verdict)))))))

(deftest out-of-allowlist-op-is-hard-hold
  (testing "an op outside the closed four-op allowlist is rejected outright, even against a clean facility"
    (let [st (store/seed-db)
          verdict (governor/check {:op :dispatch-toll-lane :subject "facility-1"}
                                  {} (clean-proposal :dispatch-toll-lane {}) st)]
      (is (true? (:hard? verdict)))
      (is (some #{:op-not-allowed} (map :rule (:violations verdict)))))))

(deftest every-allowed-op-passes-the-allowlist-check
  (doseq [op governor/allowed-ops]
    (let [st (store/seed-db)
          verdict (governor/check {:op op :subject "facility-1"} {} (clean-proposal op {}) st)]
      (is (not (some #{:op-not-allowed} (map :rule (:violations verdict))))
          (str op " must pass the closed op-allowlist check")))))

(deftest non-propose-effect-is-hard-hold
  (testing "this actor never actuates -- any :effect other than :propose is rejected outright"
    (let [st (store/seed-db)
          bad (assoc (clean-proposal :log-facility-record {}) :effect :log-facility-record/commit)
          verdict (governor/check {:op :log-facility-record :subject "facility-1"} {} bad st)]
      (is (true? (:hard? verdict)))
      (is (some #{:effect-not-propose} (map :rule (:violations verdict)))))))

(deftest structural-safety-clearance-finalization-is-hard-hold-en
  (testing "a proposal wording its way into finalizing a structural-safety clearance is a HARD, permanent block -- EN phrasing"
    (let [st (store/seed-db)
          bad (assoc (clean-proposal :schedule-facility-maintenance {})
                     :rationale "Inspection complete -- finalize the structural-safety clearance for this bridge.")
          verdict (governor/check {:op :schedule-facility-maintenance :subject "facility-1"} {} bad st)]
      (is (true? (:hard? verdict)))
      (is (some #{:structural-safety-clearance-finalization} (map :rule (:violations verdict)))))))

(deftest structural-safety-clearance-finalization-is-hard-hold-ja
  (testing "JA phrasing of the same finalization action is also caught"
    (let [st (store/seed-db)
          bad (assoc (clean-proposal :schedule-facility-maintenance {})
                     :rationale "点検の結果、当該橋梁の構造安全性クリアランスを確定しました。")
          verdict (governor/check {:op :schedule-facility-maintenance :subject "facility-1"} {} bad st)]
      (is (true? (:hard? verdict)))
      (is (some #{:structural-safety-clearance-finalization} (map :rule (:violations verdict)))))))

(deftest structural-safety-clearance-finalization-is-hard-hold-regardless-of-op
  (testing "the block applies to ANY op, not just flag-structural-safety-concern -- an unrelated op smuggling finalization language is still hard-blocked"
    (let [st (store/seed-db)
          bad (assoc (clean-proposal :log-facility-record {})
                     :summary "Certified the facility as structurally safe and issued the structural safety clearance.")
          verdict (governor/check {:op :log-facility-record :subject "facility-1"} {} bad st)]
      (is (true? (:hard? verdict)))
      (is (some #{:structural-safety-clearance-finalization} (map :rule (:violations verdict)))))))

(deftest flag-structural-safety-concern-always-escalates-even-when-clean
  (let [st (store/seed-db)
        verdict (governor/check {:op :flag-structural-safety-concern :subject "facility-1"}
                                {} (clean-proposal :flag-structural-safety-concern {}) st)]
    (is (false? (:hard? verdict)))
    (is (true? (:escalate? verdict)))
    (is (true? (:high-stakes? verdict)))
    (is (false? (:ok? verdict)) "never a clean auto-eligible :ok? true")))

(deftest supply-order-above-threshold-escalates
  (let [st (store/seed-db)
        proposal (clean-proposal :coordinate-supply-order
                                 {:kind :supply-order-proposal :cost-estimate 999999})
        verdict (governor/check {:op :coordinate-supply-order :subject "facility-1"} {} proposal st)]
    (is (false? (:hard? verdict)))
    (is (true? (:escalate? verdict)))
    (is (true? (:high-stakes? verdict)))))

(deftest supply-order-below-threshold-does-not-force-escalation
  (let [st (store/seed-db)
        proposal (clean-proposal :coordinate-supply-order
                                 {:kind :supply-order-proposal :cost-estimate 100})
        verdict (governor/check {:op :coordinate-supply-order :subject "facility-1"} {} proposal st)]
    (is (false? (:hard? verdict)))
    (is (false? (:high-stakes? verdict)))
    (is (true? (:ok? verdict)))))

(deftest low-confidence-escalates-regardless-of-op
  (let [st (store/seed-db)
        low-conf (assoc (clean-proposal :log-facility-record {}) :confidence 0.1)
        verdict (governor/check {:op :log-facility-record :subject "facility-1"} {} low-conf st)]
    (is (false? (:hard? verdict)))
    (is (true? (:escalate? verdict)))))
