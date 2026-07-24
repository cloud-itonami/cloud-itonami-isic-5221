(ns landtransport.facts-test
  (:require [clojure.test :refer [deftest is]]
            [landtransport.facts :as facts]))

(deftest jpn-has-a-spec-basis
  (is (some? (facts/spec-basis "JPN")))
  (is (string? (:provenance (facts/spec-basis "JPN")))))

(deftest all-three-seeded-jurisdictions-have-a-legal-basis
  ;; every seeded land-transport-support jurisdiction actually has a
  ;; real official legal-basis reported honestly here
  (doseq [iso3 ["JPN" "USA" "GBR"]]
    (is (some? (:legal-basis (facts/spec-basis iso3))) (str iso3 " legal-basis"))
    (is (string? (:legal-basis (facts/spec-basis iso3))) (str iso3 " legal-basis is a string"))
    (is (string? (:owner-authority (facts/spec-basis iso3))) (str iso3 " owner-authority is a string"))
    (is (string? (:provenance (facts/spec-basis iso3))) (str iso3 " provenance is a string"))
    (is (= 3 (count (:required-evidence (facts/spec-basis iso3)))) (str iso3 " required-evidence has 3 items"))))

(deftest unknown-jurisdiction-has-no-fabricated-spec-basis
  (is (nil? (facts/spec-basis "ATL"))))

(deftest coverage-never-reports-a-missing-jurisdiction-as-covered
  (let [report (facts/coverage ["JPN" "ATL" "GBR"])]
    (is (= 2 (:covered report)))
    (is (= ["ATL"] (:missing-jurisdictions report)))
    (is (= ["GBR" "JPN"] (:covered-jurisdictions report)))))

(deftest coverage-reports-exactly-three-jurisdictions-total
  (let [report (facts/coverage)]
    (is (= 3 (:covered report)))
    (is (= ["GBR" "JPN" "USA"] (:covered-jurisdictions report)))))

(deftest required-evidence-satisfied-needs-every-item
  (let [all (facts/evidence-checklist "JPN")]
    (is (facts/required-evidence-satisfied? "JPN" all))
    (is (not (facts/required-evidence-satisfied? "JPN" (rest all))))
    (is (not (facts/required-evidence-satisfied? "ATL" all)) "no spec-basis -> never satisfied")))

(deftest evidence-checklist-empty-for-unknown-jurisdiction
  (is (= [] (facts/evidence-checklist "ATL"))))
