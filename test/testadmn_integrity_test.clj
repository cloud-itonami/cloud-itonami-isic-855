;; testadmn_integrity_test — HARD CHECK 8: attendance self-contradiction
;;
;; A registered test-taker cannot be logged as BOTH :check-in AND :absent in
;; the same test session. The enrollment-binding check (HARD CHECK 6) only
;; verifies roster membership and collapses the two sets into one, so a
;; self-contradiction (the paper-trail equivalent of proxy/ghost attendance)
;; used to pass silently. HARD CHECK 8 rejects it outright.

(ns testadmn-integrity-test
  (:require [clojure.test :refer :all]
            [testadmn.store :as store]
            [testadmn.governor :as gov]
            [testadmn.operation :as op]))

(deftest test-hard-check-8-disjoint-attendance-passes
  ;; check-in / absent disjoint across enrolled test-takers still passes.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/roster #{"s001" "s002" "s003"}})
    (let [proposal {:testadmn.proposal/id "p1"
                    :testadmn.proposal/target-session-id "sess-001"
                    :testadmn.proposal/effect :propose
                    :testadmn.proposal/type :log-attendance-note
                    :testadmn.proposal/proposal-data
                    {:check-in ["s001" "s002"] :absent ["s003"]}}
          result (gov/evaluate-proposal s proposal)
          check8 (last (:checks result))]
      (is (true? (:accepted? result)))
      (is (= "all-checks-pass" (:reason result)))
      (is (= "attendance-non-contradictory" (:reason check8))))))

(deftest test-hard-check-8-same-taker-in-check-in-and-absent-rejected
  ;; Marking one test-taker as simultaneously present and absent is an
  ;; ambiguous attendance record: rejected outright, never auto-committed.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/roster #{"s001" "s002"}})
    (let [proposal {:testadmn.proposal/id "p1"
                    :testadmn.proposal/target-session-id "sess-001"
                    :testadmn.proposal/effect :propose
                    :testadmn.proposal/type :log-attendance-note
                    :testadmn.proposal/proposal-data
                    {:check-in ["s001" "s002"] :absent ["s001"]}}
          result (gov/evaluate-proposal s proposal)]
      (is (false? (:accepted? result)))
      (is (= "attendance-self-contradiction" (:reason result)))
      (is (= ["s001"] (:test-takers (last (:checks result))))))))

(deftest test-hard-check-8-non-attendance-op-passes-trivially
  ;; The check only guards :log-attendance-note; other ops pass trivially.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [proposal {:testadmn.proposal/id "p1"
                    :testadmn.proposal/target-session-id "sess-001"
                    :testadmn.proposal/effect :propose
                    :testadmn.proposal/type :schedule-test-session
                    :testadmn.proposal/proposal-data {:room "Gym A" :proctors 3}}
          result (gov/evaluate-proposal s proposal)
          check8 (last (:checks result))]
      (is (true? (:pass? check8)))
      (is (= "not-an-attendance-note" (:reason check8))))))

(deftest test-contradictory-attendance-never-auto-commits-phase3
  ;; End to end: at Phase 3 a contradictory note must be rejected, never
  ;; auto-committed, mirroring the enrollment-binding guarantee.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/roster #{"s001" "s002"}})
    (let [operation (op/make-operation :log-attendance-note "sess-001"
                                        {:check-in ["s001"] :absent ["s001"]})
          result (op/execute-operation operation s 3)]
      (is (= :rejected (:status result)))
      (is (= "attendance-self-contradiction" (:reason result))))))