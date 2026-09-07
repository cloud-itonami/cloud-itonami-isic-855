;; testadmn_accommodation_duplicate_test — HARD CHECK 16: no duplicate
;; accommodation category
;;
;; A :coordinate-accommodation-logistics must not name the same accommodation
;; category more than once. HARD CHECK 5 (logistics-only categories) only
;; requires that every named category is one of the recognized closed set; it
;; never guards against the SAME category being listed twice. Repeating a
;; category is the accommodation-side analog of HARD CHECK 13's duplicated
;; proctor, HARD CHECK 14's duplicated attendance id, and HARD CHECK 15's
;; duplicated supply item: it inflates the nominal accommodation count
;; (the number of access arrangements declared) without adding an actual
;; separate arrangement. HARD CHECK 16 rejects it outright, never held,
;; never auto-committed at Phase 3.

(ns testadmn-accommodation-duplicate-test
  (:require [clojure.test :refer [deftest is]]
            [testadmn.store :as store]
            [testadmn.governor :as gov]
            [testadmn.operation :as op]))

(defn- accommodation-proposal [data]
  {:testadmn.proposal/id "p16"
   :testadmn.proposal/type :coordinate-accommodation-logistics
   :testadmn.proposal/effect :propose
   :testadmn.proposal/target-session-id "sess-001"
   :testadmn.proposal/proposal-data data})

(defn- store-with-session []
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/roster #{"s001"}})
    s))

(deftest test-hard-check-16-distinct-accommodations-pass
  ;; Each recognized category named exactly once passes all checks.
  (let [s (store-with-session)
        result (gov/evaluate-proposal s
                 (accommodation-proposal
                   {:test-taker "s001"
                    :accommodations [:time-extension :reader/scribe]}))]
    (is (true? (:accepted? result)))
    (is (= "all-checks-pass" (:reason result)))))

(deftest test-hard-check-16-duplicate-accommodation-rejected
  ;; Naming the SAME accommodation category twice is access-fabrication:
  ;; rejected outright, never auto-committed at Phase 3.
  (let [s (store-with-session)
        result (gov/evaluate-proposal s
                 (accommodation-proposal
                   {:test-taker "s001"
                    :accommodations [:time-extension :time-extension]}))]
    (is (false? (:accepted? result)))
    (is (= "accommodation-duplicate-category" (:reason result)))))

(deftest test-hard-check-16-sequence-duplicate-rejected
  ;; A duplicate is a duplicate whether the categories are adjacent or not;
  ;; the whole declared list is inspected, not a deduped set.
  (let [s (store-with-session)
        result (gov/evaluate-proposal s
                 (accommodation-proposal
                   {:test-taker "s001"
                    :accommodations [:accessible-room :reader/scribe :accessible-room]}))]
    (is (false? (:accepted? result)))
    (is (= "accommodation-duplicate-category" (:reason result)))))

(deftest test-hard-check-16-guards-only-accommodation-op
  ;; HARD CHECK 16 applies only to :coordinate-accommodation-logistics; other
  ;; ops pass trivially.
  (let [s (store/new-mem-store)
        _ (store/register-session! s "sess-001"
             {:testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
              :testadmn.test-session/facility-id "facility-101"})
        proposal {:testadmn.proposal/id "op16"
                  :testadmn.proposal/type :schedule-test-session
                  :testadmn.proposal/effect :propose
                  :testadmn.proposal/target-session-id "sess-001"
                  :testadmn.proposal/proposal-data {:room "Gym A"}}
        result (gov/evaluate-proposal s proposal)
        check16 (nth (:checks result) 14)]
    (is (true? (:pass? check16)))
    (is (= "not-an-accommodation" (:reason check16)))))

(deftest test-hard-check-16-duplicate-never-auto-commits-phase3
  ;; End to end: at Phase 3 a duplicated accommodation category is rejected
  ;; by the governor and never auto-committed.
  (let [s (store-with-session)
        operation (op/make-operation :coordinate-accommodation-logistics "sess-001"
                                     {:test-taker "s001"
                                      :accommodations [:time-extension :time-extension]})
        result (op/execute-operation operation s 3)]
    (is (= :rejected (:status result)))
    (is (= "accommodation-duplicate-category" (:reason result)))))

(deftest test-check8-still-last-after-check16
  ;; HARD CHECK 8 stays the LAST entry of the governor's :checks vector, so
  ;; testadmn_integrity_test's (last :checks) contract keeps holding after
  ;; HARD CHECK 16 is added.
  (let [s (store-with-session)
        _ (store/register-session! s "sess-002"
             {:testadmn.test-session/roster #{"s001" "s002"}})
        proposal {:testadmn.proposal/id "p16b"
                  :testadmn.proposal/type :log-attendance-note
                  :testadmn.proposal/effect :propose
                  :testadmn.proposal/target-session-id "sess-002"
                  :testadmn.proposal/proposal-data {:check-in ["s001"] :absent ["s002"]}}
        result (gov/evaluate-proposal s proposal)
        check8 (last (:checks result))]
    (is (= "attendance-non-contradictory" (:reason check8)))))