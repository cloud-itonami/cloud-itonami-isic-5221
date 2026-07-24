(ns landtransport.registry-test
  (:require [clojure.test :refer [deftest is]]
            [landtransport.registry :as r]))

;; ----------------------------- recovery-capacity range-check pure function -----------------------------

(deftest recovery-capacity-exceeded-vs-tow-rating
  (is (not (r/recovery-capacity-exceeded? 5000 3200)) "recovered weight within tow capacity -> ok")
  (is (not (r/recovery-capacity-exceeded? 5000 5000)) "recovered weight exactly at tow capacity -> ok")
  (is (r/recovery-capacity-exceeded? 5000 8000) "recovered weight exceeds tow capacity -> exceeded")
  (is (r/recovery-capacity-exceeded? 0 1) "zero capacity, any recovered weight -> exceeded")
  (is (r/recovery-capacity-exceeded? nil 3200) "missing tow capacity -> unsafe")
  (is (r/recovery-capacity-exceeded? 5000 nil) "missing recovered weight -> unsafe")
  (is (r/recovery-capacity-exceeded? nil nil) "missing both -> unsafe"))

;; ----------------------------- register-authorization-record -----------------------------

(deftest authorization-is-a-draft-not-a-real-authorization
  (let [result (r/register-authorization-record "dispatch-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest authorization-assigns-authorization-number
  (let [result (r/register-authorization-record "dispatch-1" "JPN" 7)]
    (is (= (get result "authorization_number") "JPN-DISPATCH-000007"))
    (is (= (get-in result ["record" "land_dispatch_id"]) "dispatch-1"))
    (is (= (get-in result ["record" "kind"]) "dispatch-authorization-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest authorization-validation-rules
  (is (thrown? Exception (r/register-authorization-record "" "JPN" 0)))
  (is (thrown? Exception (r/register-authorization-record "dispatch-1" "" 0)))
  (is (thrown? Exception (r/register-authorization-record "dispatch-1" "JPN" -1))))

;; ----------------------------- register-reconciliation-record -----------------------------

(deftest reconciliation-is-a-draft-not-a-real-publication
  (let [result (r/register-reconciliation-record "dispatch-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest reconciliation-assigns-reconciliation-number
  (let [result (r/register-reconciliation-record "dispatch-1" "JPN" 7)]
    (is (= (get result "reconciliation_number") "JPN-RECON-000007"))
    (is (= (get-in result ["record" "land_dispatch_id"]) "dispatch-1"))
    (is (= (get-in result ["record" "kind"]) "reconciliation-publish-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest reconciliation-validation-rules
  (is (thrown? Exception (r/register-reconciliation-record "" "JPN" 0)))
  (is (thrown? Exception (r/register-reconciliation-record "dispatch-1" "" 0)))
  (is (thrown? Exception (r/register-reconciliation-record "dispatch-1" "JPN" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-authorization-record "dispatch-1" "JPN" 0)
        hist (r/append [] c1)
        c2 (r/register-authorization-record "dispatch-2" "JPN" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-DISPATCH-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-DISPATCH-000001" (get-in hist2 [1 "record_id"])))))
