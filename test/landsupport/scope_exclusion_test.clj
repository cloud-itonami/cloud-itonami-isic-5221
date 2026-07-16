(ns landsupport.scope-exclusion-test
  "Dedicated regression test for a bug class multiple sibling actors in
  this fleet have independently discovered and fixed: a governor's own
  scope-exclusion term list phrased as a BARE NOUN (e.g. 'safety',
  'structural') can accidentally match inside the mock advisor's own
  DEFAULT rationale/disclaimer text for a legitimate, allowed proposal
  -- causing the actor to self-block on its own happy path.

  `landsupport.governor/finalization-phrases` is deliberately phrased
  as the finalization/execution ACTION ('finalize the structural-
  safety clearance', not the bare noun 'safety') specifically to avoid
  this. This test asserts the invariant holds: for every op in the
  closed allowlist, the DEFAULT mock advisor's proposal against a
  clean, registered+verified facility never trips
  `structural-safety-clearance-finalization-violations` --
  particularly `:flag-structural-safety-concern`, whose entire purpose
  is to talk ABOUT a structural-safety concern in its rationale
  without ever finalizing one."
  (:require [clojure.test :refer [deftest is testing]]
            [landsupport.advisor :as advisor]
            [landsupport.governor :as governor]
            [landsupport.store :as store]))

(def ^:private requests-by-op
  {:log-facility-record            {:op :log-facility-record :subject "facility-1"
                                    :entry {:kind :toll-transaction :amount 350}}
   :schedule-facility-maintenance  {:op :schedule-facility-maintenance :subject "facility-1"
                                    :window {:start "2026-08-01" :end "2026-08-03"}}
   :flag-structural-safety-concern {:op :flag-structural-safety-concern :subject "facility-1"
                                    :observation "hairline crack observed near expansion joint"}
   :coordinate-supply-order        {:op :coordinate-supply-order :subject "facility-1"
                                    :materials ["barrier tape" "signage"] :cost-estimate 1200}})

(deftest default-mock-advisor-proposals-never-self-trip-scope-exclusion
  (testing "every allowlisted op's default proposal, against a clean facility, is FREE of the structural-safety-clearance-finalization violation"
    (let [st (store/seed-db)]
      (doseq [[op request] requests-by-op]
        (let [proposal (advisor/infer st request)
              verdict (governor/check request {} proposal st)]
          (is (not (some #{:structural-safety-clearance-finalization} (map :rule (:violations verdict))))
              (str op " must never self-trip the structural-safety-clearance-finalization scope-exclusion check. "
                   "proposal rationale was: " (pr-str (:rationale proposal)))))))))

(deftest flag-structural-safety-concern-specifically-never-self-trips
  (testing "the op whose entire job is to talk about a structural-safety concern must never be mistaken for finalizing one"
    (let [st (store/seed-db)
          request (:flag-structural-safety-concern requests-by-op)
          proposal (advisor/infer st request)
          verdict (governor/check request {} proposal st)]
      (is (false? (:hard? verdict))
          (str "flag-structural-safety-concern must not HARD-hold on its own happy path. violations: "
               (pr-str (:violations verdict))))
      (is (true? (:escalate? verdict)) "it must still always escalate (a separate, correct invariant)"))))

(deftest default-mock-advisor-proposals-are-not-hard-blocked-when-facility-is-clean
  (testing "regression guard: none of the four default proposals against a clean, registered+verified facility ever produce ANY hard violation"
    (let [st (store/seed-db)]
      (doseq [[op request] requests-by-op]
        (let [proposal (advisor/infer st request)
              verdict (governor/check request {} proposal st)]
          (is (false? (:hard? verdict))
              (str op " must not HARD-hold on its own default happy-path proposal. violations: "
                   (pr-str (:violations verdict)))))))))
