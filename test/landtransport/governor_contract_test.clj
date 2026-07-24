(ns landtransport.governor-contract-test
  "The governor contract as executable tests. The single invariant
  under test:

    Land Transport Support Advisor never authorizes a dispatch or
    publishes a reconciliation record the Land Transport Support
    Governor would reject, `:dispatch/authorize`/
    `:reconciliation/publish` NEVER auto-commit at any phase,
    `:safety-scope/intake` (no dispatch risk) MAY auto-commit when
    clean, and every decision (commit OR hold) leaves exactly one
    ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [landtransport.store :as store]
            [landtransport.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :depot-superintendent :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- verify!
  "Walks `subject` through safety-scope verify -> approve, leaving an
  assessment on file. Uses distinct thread-ids per call site by
  suffixing `tid-prefix`."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-verify") {:op :safety-scope/verify :subject subject} operator)
  (approve! actor (str tid-prefix "-verify")))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :safety-scope/intake :subject "dispatch-1"
                   :patch {:id "dispatch-1" :plaza-or-terminal-id "TOLL-42"}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "TOLL-42" (:plaza-or-terminal-id (store/land-dispatch db "dispatch-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest safety-scope-verify-always-needs-approval
  (testing "safety-scope verify is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :safety-scope/verify :subject "dispatch-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/assessment-of db "dispatch-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a safety-scope/verify proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :safety-scope/verify :subject "dispatch-2"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/assessment-of db "dispatch-2")) "no assessment written"))))

(deftest dispatch-authorize-without-assessment-is-held
  (testing "dispatch/authorize before any safety-scope verification -> HOLD (evidence incomplete)"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :dispatch/authorize :subject "dispatch-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (-> (store/ledger db) first :basis))))))

(deftest recovery-job-without-vehicle-condition-check-is-held-and-unoverridable
  (testing "a recovery job whose vehicle-condition check is not complete -> HOLD, and never reaches request-approval"
    (let [[db actor] (fresh)
          _ (verify! actor "t5pre" "dispatch-3")
          res (exec-op actor "t5" {:op :dispatch/authorize :subject "dispatch-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:dispatch-precondition-unmet} (-> (store/ledger db) last :basis)))
      (is (empty? (store/act1-history db))))))

(deftest recovery-capacity-exceeded-is-held-and-unoverridable
  (testing "a recovery job whose recovered vehicle exceeds the tow vehicle's rated capacity -> HOLD, and never reaches request-approval"
    (let [[db actor] (fresh)
          _ (verify! actor "t6pre" "dispatch-4")
          res (exec-op actor "t6" {:op :dispatch/authorize :subject "dispatch-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:recovery-capacity-exceeded} (-> (store/ledger db) last :basis)))
      (is (empty? (store/act1-history db))))))

(deftest terminal-slot-without-verified-evidence-is-held-and-unoverridable
  (testing "a terminal-slot operation without verified evidence -> HOLD, and never reaches request-approval"
    (let [[db actor] (fresh)
          _ (verify! actor "t7pre" "dispatch-5")
          res (exec-op actor "t7" {:op :dispatch/authorize :subject "dispatch-5"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:dispatch-precondition-unmet} (-> (store/ledger db) last :basis)))
      (is (empty? (store/act1-history db))))))

(deftest dispatch-authorize-always-escalates-then-human-decides
  (testing "a clean, fully-verified toll-lane record still ALWAYS interrupts for human approval -- :dispatch/authorize is never auto"
    (let [[db actor] (fresh)
          _ (verify! actor "t8pre" "dispatch-1")
          r1 (exec-op actor "t8" {:op :dispatch/authorize :subject "dispatch-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, authorization record drafted"
        (let [r2 (approve! actor "t8")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:dispatched? (store/land-dispatch db "dispatch-1"))))
          (is (= 1 (count (store/act1-history db))) "one draft authorization record"))))))

(deftest reconciliation-publish-always-escalates-then-human-decides
  (testing "a clean, fully-verified, already-dispatched record still ALWAYS interrupts for human approval -- :reconciliation/publish is never auto"
    (let [[db actor] (fresh)
          _ (verify! actor "t9pre" "dispatch-1")
          _ (exec-op actor "t9commit" {:op :dispatch/authorize :subject "dispatch-1"} operator)
          _ (approve! actor "t9commit")
          r1 (exec-op actor "t9" {:op :reconciliation/publish :subject "dispatch-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, reconciliation record drafted"
        (let [r2 (approve! actor "t9")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:reconciliation-published? (store/land-dispatch db "dispatch-1"))))
          (is (= 1 (count (store/act2-history db))) "one draft reconciliation record"))))))

(deftest dispatch-authorize-double-dispatch-is-held
  (testing "authorizing a dispatch for the same record twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (verify! actor "t10pre" "dispatch-1")
          _ (exec-op actor "t10a" {:op :dispatch/authorize :subject "dispatch-1"} operator)
          _ (approve! actor "t10a")
          res (exec-op actor "t10" {:op :dispatch/authorize :subject "dispatch-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-dispatched} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/act1-history db))) "still only the one earlier authorization"))))

(deftest reconciliation-publish-double-publish-is-held
  (testing "publishing a reconciliation record for the same record twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (verify! actor "t11pre" "dispatch-1")
          _ (exec-op actor "t11commit" {:op :dispatch/authorize :subject "dispatch-1"} operator)
          _ (approve! actor "t11commit")
          _ (exec-op actor "t11a" {:op :reconciliation/publish :subject "dispatch-1"} operator)
          _ (approve! actor "t11a")
          res (exec-op actor "t11" {:op :reconciliation/publish :subject "dispatch-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-reconciled} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/act2-history db))) "still only the one earlier reconciliation"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :safety-scope/intake :subject "dispatch-1"
                          :patch {:id "dispatch-1" :plaza-or-terminal-id "TOLL-42"}} operator)
      (exec-op actor "b" {:op :safety-scope/verify :subject "dispatch-2"} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
