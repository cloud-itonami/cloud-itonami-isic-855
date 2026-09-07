;; testadmn_attendance_duplicate_test — HARD CHECK 14: no duplicate attendance test-taker
;;
;; A :log-attendance-note must not name the same test-taker id more than once
;; within :check-in or within :absent. HARD CHECK 6 (enrollment binding) collects
;; :check-in and :absent into ONE set for membership, and HARD CHECK 8 requires
;; the two to be disjoint — but neither guards against the SAME id being repeated
;; WITHIN :check-in (or within :absent), which sets silently collapse. Repeating
;; an id inflates the nominal attendance count without adding an actual seated
;; body — the attendance-side analog of HARD CHECK 13's duplicated proctor.
;; HARD CHECK 14 rejects it outright, never held, never auto-committed at Phase 3.

(ns testadmn-attendance-duplicate-test
  (:require [clojure.test :refer [deftest is]]
            [testadmn.store :as store]
            [testadmn.governor :as gov]
            [testadmn.operation :as op]))

(defn- note-proposal [data]
  {:testadmn.proposal/id "p14"
   :testadmn.proposal/type :log-attendance-note
   :testadmn.proposal/effect :propose
   :testadmn.proposal/target-session-id "sess-001"
   :testadmn.proposal/proposal-data data})

(defn- store-with-roster []
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/roster #{"s001" "s002" "s003"}})
    s))

(deftest test-hard-check-14-distinct-attendance-passes
  (let [s (store-with-roster)
        result (gov/evaluate-proposal s
                 (note-proposal {:check-in ["s001" "s002"] :absent ["s003"]}))]
    (is (true? (:accepted? result)))
    (is (= "all-checks-pass" (:reason result)))))

(deftest test-hard-check-14-duplicate-check-in-rejected
  ;; The same test-taker listed twice in :check-in fabricates a second seated
  ;; body: rejected outright, never auto-committed at Phase 3.
  (let [s (store-with-roster)
        result (gov/evaluate-proposal s
                 (note-proposal {:check-in ["s001" "s001"] :absent ["s003"]}))]
    (is (false? (:accepted? result)))
    (is (= "attendance-duplicate-check-in" (:reason result)))))

(deftest test-hard-check-14-duplicate-absent-rejected
  ;; A repeated id in :absent is equally a fabricated paper-trail record.
  (let [s (store-with-roster)
        result (gov/evaluate-proposal s
                 (note-proposal {:check-in ["s001"] :absent ["s002" "s002"]}))]
    (is (false? (:accepted? result)))
    (is (= "attendance-duplicate-absent" (:reason result)))))

(deftest test-hard-check-14-guards-only-attendance-op
  ;; HARD CHECK 14 applies only to :log-attendance-note; other ops pass trivially.
  (let [s (store-with-roster)
        proposal {:testadmn.proposal/id "op14"
                  :testadmn.proposal/type :schedule-test-session
                  :testadmn.proposal/effect :propose
                  :testadmn.proposal/target-session-id "sess-001"
                  :testadmn.proposal/proposal-data {:room "Gym A" :proctors 3}}
        result (gov/evaluate-proposal s proposal)
        check14 (nth (:checks result) 12)]
    (is (true? (:pass? check14)))
    (is (= "not-an-attendance-note" (:reason check14)))))

(deftest test-hard-check-14-duplicate-never-auto-commits-phase3
  ;; At Phase 3 a duplicated attendance id must be rejected, never auto-committed.
  (let [s (store-with-roster)
        operation (op/make-operation :log-attendance-note "sess-001"
                                     {:check-in ["s001" "s001"] :absent ["s003"]})
        result (op/execute-operation operation s 3)]
    (is (= :rejected (:status result)))
    (is (= "attendance-duplicate-check-in" (:reason result)))))

(deftest test-check8-still-last-after-check14
  ;; HARD CHECK 8 stays the LAST entry of the :checks vector so
  ;; testadmn_integrity_test's (last :checks) contract keeps holding.
  (let [s (store-with-roster)
        result (gov/evaluate-proposal s
                 (note-proposal {:check-in ["s001"] :absent ["s002"]}))
        check8 (last (:checks result))]
    (is (= "attendance-non-contradictory" (:reason check8)))))