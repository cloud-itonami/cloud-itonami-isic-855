;; testadmn_supply_duplicate_test — HARD CHECK 15: no duplicate supply item
;;
;; A :coordinate-supply-request must not name the same consumable more than
;; once. HARD CHECK 7 (bounded consumable allowlist) only requires that every
;; item is a recognized non-content consumable; it never guards against the
;; SAME item being listed twice. Repeating an item is the supply-side analog
;; of HARD CHECK 13's duplicated proctor and HARD CHECK 14's duplicated
;; attendance id:it inflates the nominal supply count (and the consumed-stock
;; ledger) without adding an actual physical delivery item to the room.
;; HARD CHECK 15 rejects it outright, never held, never auto-committed at
;; Phase 3.

(ns testadmn-supply-duplicate-test
  (:require [clojure.test :refer [deftest is]]
            [testadmn.store :as store]
            [testadmn.governor :as gov]
            [testadmn.operation :as op]))

(defn- supply-proposal [data]
  {:testadmn.proposal/id "p15"
   :testadmn.proposal/type :coordinate-supply-request
   :testadmn.proposal/effect :propose
   :testadmn.proposal/target-session-id "sess-001"
   :testadmn.proposal/proposal-data data})

(defn- store-with-session []
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    s))

(deftest test-hard-check-15-distinct-supplies-pass
  ;; Each recognized consumable named exactly once passes all checks.
  (let [s (store-with-session)
        result (gov/evaluate-proposal s
                 (supply-proposal {:supplies ["answer-sheets" "pencils" "scratch-paper"]}))]
    (is (true? (:accepted? result)))
    (is (= "all-checks-pass" (:reason result)))))

(deftest test-hard-check-15-duplicate-supply-rejected
  ;; Naming the SAME consumable twice is inventory fabrication: rejected
  ;; outright, never auto-committed at Phase 3.
  (let [s (store-with-session)
        result (gov/evaluate-proposal s
                 (supply-proposal {:supplies ["answer-sheets" "answer-sheets"]}))]
    (is (false? (:accepted? result)))
    (is (= "supply-duplicate-item" (:reason result)))))

(deftest test-hard-check-15-case-insensitive-duplicate-rejected
  ;; HARD CHECK 7 normalizes items case-insensitively; HARD CHECK 15 mirrors
  ;; that so a differently-cased repeat is still a duplicate.
  (let [s (store-with-session)
        result (gov/evaluate-proposal s
                 (supply-proposal {:supplies ["Answer-Sheets" "answer-sheets"]}))]
    (is (false? (:accepted? result)))
    (is (= "supply-duplicate-item" (:reason result)))))

(deftest test-hard-check-15-guards-only-supply-op
  ;; HARD CHECK 15 applies only to :coordinate-supply-request; other ops pass
  ;; trivially.
  (let [s (store/new-mem-store)
        _ (store/register-session! s "sess-001"
             {:testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
              :testadmn.test-session/facility-id "facility-101"})
        proposal {:testadmn.proposal/id "op15"
                  :testadmn.proposal/type :schedule-test-session
                  :testadmn.proposal/effect :propose
                  :testadmn.proposal/target-session-id "sess-001"
                  :testadmn.proposal/proposal-data {:room "Gym A"}}
        result (gov/evaluate-proposal s proposal)
        check15 (nth (:checks result) 13)]
    (is (true? (:pass? check15)))
    (is (= "not-a-supply-request" (:reason check15)))))

(deftest test-hard-check-15-duplicate-never-auto-commits-phase3
  ;; End to end: at Phase 3 a duplicated supply request is rejected by the
  ;; governor and never auto-committed.
  (let [s (store-with-session)
        operation (op/make-operation :coordinate-supply-request "sess-001"
                                     {:supplies ["answer-sheets" "answer-sheets"]})
        result (op/execute-operation operation s 3)]
    (is (= :rejected (:status result)))
    (is (= "supply-duplicate-item" (:reason result)))))

(deftest test-check8-still-last-after-check15
  ;; HARD CHECK 8 stays the LAST entry of the governor's :checks vector, so
  ;; testadmn_integrity_test's (last :checks) contract keeps holding after
  ;; HARD CHECK 15 is added.
  (let [s (store-with-session)
        _ (store/register-session! s "sess-002"
             {:testadmn.test-session/roster #{"s001" "s002"}})
        proposal {:testadmn.proposal/id "p15b"
                  :testadmn.proposal/type :log-attendance-note
                  :testadmn.proposal/effect :propose
                  :testadmn.proposal/target-session-id "sess-002"
                  :testadmn.proposal/proposal-data {:check-in ["s001"] :absent ["s002"]}}
        result (gov/evaluate-proposal s proposal)
        check8 (last (:checks result))]
    (is (= "attendance-non-contradictory" (:reason check8)))))