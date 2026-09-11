(ns landtransport.governor-test
  "Unit-level tests for the Land Transport Support Governor's checks,
  direct calls to the (private) check fns and to `governor/check` -- the
  same idiom `terminal.governor-test` (cloud-itonami-isic-5210) uses for
  its own dedicated per-check unit tests. End-to-end contract coverage
  (via the actor graph, approvals and the audit ledger) lives in
  `governor_contract_test.clj`."
  (:require [clojure.test :refer [deftest is testing]]
            [landtransport.governor :as governor]
            [landtransport.store :as store]))

;; ----------------------------- spec-basis-violations (unit) -----------------------------

(deftest spec-basis-violations-test
  (testing "no :cites at all -> hard violation"
    (let [violations (#'governor/spec-basis-violations {:op :safety-scope/verify}
                                                        {:cites [] :value {}})]
      (is (seq violations))
      (is (= :no-spec-basis (-> violations first :rule)))))

  (testing "explicit nil :spec-basis under :value -> hard violation"
    (let [violations (#'governor/spec-basis-violations {:op :safety-scope/verify}
                                                        {:cites ["something"] :value {:spec-basis nil}})]
      (is (seq violations))))

  (testing "cited proposal -> no violation"
    (let [violations (#'governor/spec-basis-violations {:op :safety-scope/verify}
                                                        {:cites ["法的根拠"] :value {:spec-basis "https://example"}})]
      (is (empty? violations))))

  (testing "only applies to :safety-scope/verify / :dispatch/authorize / :reconciliation/publish"
    (let [violations (#'governor/spec-basis-violations {:op :safety-scope/intake}
                                                        {:cites [] :value {}})]
      (is (empty? violations)))))

;; ----------------------------- evidence-incomplete-violations (unit) -----------------------------

(deftest evidence-incomplete-violations-test
  (testing "no assessment on file -> hard violation"
    (let [st (store/seed-db)
          violations (#'governor/evidence-incomplete-violations
                      {:op :dispatch/authorize :subject "dispatch-1"} st)]
      (is (seq violations))
      (is (= :evidence-incomplete (-> violations first :rule)))))

  (testing "assessment on file with full checklist -> no violation"
    (let [st (store/seed-db)]
      (store/commit-record! st {:effect :safety-scope-assessment/set
                                :path ["dispatch-1"]
                                :payload {:jurisdiction "JPN" :checklist ["toll plaza / bus terminal safety-scope registration"
                                                                          "vehicle-recovery operator licensing record"
                                                                          "multi-carrier terminal-slot booking evidence"]}})
      (let [violations (#'governor/evidence-incomplete-violations
                        {:op :dispatch/authorize :subject "dispatch-1"} st)]
        (is (empty? violations)))))

  (testing "assessment on file with partial checklist -> hard violation"
    (let [st (store/seed-db)]
      (store/commit-record! st {:effect :safety-scope-assessment/set
                                :path ["dispatch-1"]
                                :payload {:jurisdiction "JPN" :checklist ["toll plaza / bus terminal safety-scope registration"]}})
      (let [violations (#'governor/evidence-incomplete-violations
                        {:op :dispatch/authorize :subject "dispatch-1"} st)]
        (is (seq violations)))))

  (testing "only applies to :dispatch/authorize / :reconciliation/publish"
    (let [st (store/seed-db)
          violations (#'governor/evidence-incomplete-violations
                      {:op :safety-scope/intake :subject "dispatch-1"} st)]
      (is (empty? violations)))))

;; ----------------------------- dispatch-precondition-violations (unit) -----------------------------

(deftest dispatch-precondition-violations-test
  (testing "recovery-job with vehicle-condition-checked? false -> hard violation"
    (let [st (store/seed-db)
          violations (#'governor/dispatch-precondition-violations
                      {:op :dispatch/authorize :subject "dispatch-3"} st)]
      (is (seq violations))
      (is (= :dispatch-precondition-unmet (-> violations first :rule)))))

  (testing "recovery-job with vehicle-condition-checked? true -> no violation"
    (let [st (store/seed-db)]
      (store/commit-record! st {:effect :dispatch/upsert :value {:id "dispatch-3" :vehicle-condition-checked? true}})
      (let [violations (#'governor/dispatch-precondition-violations
                        {:op :dispatch/authorize :subject "dispatch-3"} st)]
        (is (empty? violations)))))

  (testing "terminal-slot with terminal-slot-evidence-verified? false -> hard violation"
    (let [st (store/seed-db)
          violations (#'governor/dispatch-precondition-violations
                      {:op :dispatch/authorize :subject "dispatch-5"} st)]
      (is (seq violations))
      (is (= :dispatch-precondition-unmet (-> violations first :rule)))))

  (testing "terminal-slot with terminal-slot-evidence-verified? true -> no violation"
    (let [st (store/seed-db)]
      (store/commit-record! st {:effect :dispatch/upsert :value {:id "dispatch-5" :terminal-slot-evidence-verified? true}})
      (let [violations (#'governor/dispatch-precondition-violations
                        {:op :dispatch/authorize :subject "dispatch-5"} st)]
        (is (empty? violations)))))

  (testing "toll-lane has no extra precondition beyond safety scope -> no violation"
    (let [st (store/seed-db)
          violations (#'governor/dispatch-precondition-violations
                      {:op :dispatch/authorize :subject "dispatch-1"} st)]
      (is (empty? violations))))

  (testing "unrecognized dispatch-kind -> hard violation (missing-data-is-unsafe)"
    (let [st (store/seed-db)]
      (store/commit-record! st {:effect :dispatch/upsert :value {:id "dispatch-1" :dispatch-kind :something-else}})
      (let [violations (#'governor/dispatch-precondition-violations
                        {:op :dispatch/authorize :subject "dispatch-1"} st)]
        (is (seq violations)))))

  (testing "only applies to :dispatch/authorize"
    (let [st (store/seed-db)
          violations (#'governor/dispatch-precondition-violations
                      {:op :reconciliation/publish :subject "dispatch-3"} st)]
      (is (empty? violations)))))

;; ----------------------------- recovery-capacity-exceeded-violations (unit) -----------------------------

(deftest recovery-capacity-exceeded-violations-test
  (testing "recovery-job whose recovered weight exceeds tow capacity -> hard violation"
    (let [st (store/seed-db)
          violations (#'governor/recovery-capacity-exceeded-violations
                      {:op :dispatch/authorize :subject "dispatch-4"} st)]
      (is (seq violations))
      (is (= :recovery-capacity-exceeded (-> violations first :rule)))))

  (testing "recovery-job whose recovered weight fits tow capacity -> no violation"
    (let [st (store/seed-db)
          violations (#'governor/recovery-capacity-exceeded-violations
                      {:op :dispatch/authorize :subject "dispatch-3"} st)]
      (is (empty? violations))))

  (testing "non-recovery-job kinds are never subject to this check"
    (let [st (store/seed-db)
          violations (#'governor/recovery-capacity-exceeded-violations
                      {:op :dispatch/authorize :subject "dispatch-1"} st)]
      (is (empty? violations))))

  (testing "only applies to :dispatch/authorize"
    (let [st (store/seed-db)
          violations (#'governor/recovery-capacity-exceeded-violations
                      {:op :reconciliation/publish :subject "dispatch-4"} st)]
      (is (empty? violations)))))

;; ----------------------------- already-dispatched-violations / already-reconciled-violations (unit) -----------------------------

(deftest already-dispatched-violations-test
  (testing "not yet dispatched -> no violation"
    (let [st (store/seed-db)]
      (is (empty? (#'governor/already-dispatched-violations {:op :dispatch/authorize :subject "dispatch-1"} st)))))

  (testing "already dispatched -> hard violation"
    (let [st (store/seed-db)]
      (store/commit-record! st {:effect :dispatch/upsert :value {:id "dispatch-1" :dispatched? true}})
      (let [violations (#'governor/already-dispatched-violations {:op :dispatch/authorize :subject "dispatch-1"} st)]
        (is (seq violations))
        (is (= :already-dispatched (-> violations first :rule)))))))

(deftest already-reconciled-violations-test
  (testing "not yet reconciled -> no violation"
    (let [st (store/seed-db)]
      (is (empty? (#'governor/already-reconciled-violations {:op :reconciliation/publish :subject "dispatch-1"} st)))))

  (testing "already reconciled -> hard violation"
    (let [st (store/seed-db)]
      (store/commit-record! st {:effect :dispatch/upsert :value {:id "dispatch-1" :reconciliation-published? true}})
      (let [violations (#'governor/already-reconciled-violations {:op :reconciliation/publish :subject "dispatch-1"} st)]
        (is (seq violations))
        (is (= :already-reconciled (-> violations first :rule)))))))

;; ----------------------------- confidence floor / high-stakes (check) -----------------------------

(deftest check-escalates-below-confidence-floor
  (let [st (store/seed-db)
        request {:op :safety-scope/intake :subject "dispatch-1"}
        proposal {:cites ["a"] :confidence 0.1 :stake nil :value {}}
        verdict (governor/check request {:actor-id "test"} proposal st)]
    (is (false? (:hard? verdict)))
    (is (true? (:escalate? verdict)))
    (is (false? (:ok? verdict)))))

(deftest check-escalates-high-stakes-even-when-clean
  (let [st (store/seed-db)]
    (store/commit-record! st {:effect :safety-scope-assessment/set
                              :path ["dispatch-1"]
                              :payload {:jurisdiction "JPN" :checklist ["toll plaza / bus terminal safety-scope registration"
                                                                        "vehicle-recovery operator licensing record"
                                                                        "multi-carrier terminal-slot booking evidence"]}})
    (let [request {:op :dispatch/authorize :subject "dispatch-1" :stake :dispatch/authorize}
          proposal {:cites ["dispatch-1"] :confidence 0.9 :stake :dispatch/authorize :value {}}
          verdict (governor/check request {:actor-id "test"} proposal st)]
      (is (false? (:hard? verdict)))
      (is (true? (:high-stakes? verdict)))
      (is (true? (:escalate? verdict)))
      (is (false? (:ok? verdict))))))
