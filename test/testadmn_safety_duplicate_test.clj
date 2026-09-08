;; testadmn_safety_duplicate_test — HARD CHECK 23: no duplicate
;; safety-concern category
;;
;; A :flag-safety-concern must not declare the same safety-concern category
;; more than once. HARD CHECK 11 (safety category) only requires every declared
;; category to be one of the recognized closed set (:facility-hazard,
;; :test-taker-wellbeing, :integrity-incident, :environmental-hazard); it never
;; guards against the SAME category being declared twice. Repeating a category
;; is the safety-side analog of HARD CHECK 13's duplicated proctor, HARD
;; CHECK 14's duplicated attendance id, HARD CHECK 15's duplicated supply item,
;; and HARD CHECK 16's duplicated accommodation category: it inflates the
;; nominal number of distinct concerns (and thus the escalation/triage
;; workload) without adding an actual separate incident. HARD CHECK 23 rejects
;; it outright, never held, never auto-committed at Phase 3.

(ns testadmn-safety-duplicate-test
  (:require [clojure.test :refer [deftest is]]
            [testadmn.store :as store]
            [testadmn.governor :as gov]))

(defn- safety-proposal [data]
  {:testadmn.proposal/id "p23"
   :testadmn.proposal/type :flag-safety-concern
   :testadmn.proposal/effect :propose
   :testadmn.proposal/target-session-id "sess-001"
   :testadmn.proposal/proposal-data data})

(defn- store-with-session []
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-101"
       :testadmn.test-session/roster #{"s001"}})
    s))

(deftest test-hard-check-23-distinct-safety-categories-pass
  ;; Each recognized category named exactly once passes all checks.
  (let [s (store-with-session)
        result (gov/evaluate-proposal s
                 (safety-proposal
                   {:concern "possible integrity issue at station 5"
                    :safety-concerns [:integrity-incident :facility-hazard]
                    :facility-id "station-5"}))]
    (is (true? (:accepted? result)))
    (is (= "flag-safety-concern-escalates" (:reason result)))))

(deftest test-hard-check-23-duplicate-safety-category-rejected
  ;; Naming the SAME safety-concern category twice is escalation-inflation:
  ;; rejected outright, never auto-committed at Phase 3.
  (let [s (store-with-session)
        result (gov/evaluate-proposal s
                 (safety-proposal
                   {:concern "possible integrity issue at station 5"
                    :safety-concerns [:integrity-incident :integrity-incident]
                    :facility-id "station-5"}))]
    (is (false? (:accepted? result)))
    (is (= "safety-concern-duplicate-category" (:reason result)))))

(deftest test-hard-check-23-sequence-duplicate-rejected
  ;; A duplicate is a duplicate whether the categories are adjacent or not;
  ;; the whole declared list is inspected, not a deduped set.
  (let [s (store-with-session)
        result (gov/evaluate-proposal s
                 (safety-proposal
                   {:concern "possible facility hazard, then integrity issue"
                    :safety-concerns [:facility-hazard :integrity-incident :facility-hazard]
                    :facility-id "station-5"}))]
    (is (false? (:accepted? result)))
    (is (= "safety-concern-duplicate-category" (:reason result)))))

(deftest test-hard-check-23-guards-only-safety-op
  ;; HARD CHECK 23 applies only to :flag-safety-concern; other ops pass
  ;; trivially even if they carry a duplicated-looking category list.
  (let [s (store-with-session)
        proposal {:testadmn.proposal/id "op23"
                  :testadmn.proposal/type :coordinate-accommodation-logistics
                  :testadmn.proposal/effect :propose
                  :testadmn.proposal/target-session-id "sess-001"
                  :testadmn.proposal/proposal-data
                  {:test-taker "s001" :accommodations [:time-extension :time-extension]}}
        result (gov/evaluate-proposal s proposal)]
    (is (false? (:accepted? result)))
    (is (= "accommodation-duplicate-category" (:reason result)))))
