;; testadmn_attendance_reconcile_test — HARD CHECK 22: attendance reconciliation
;;
;; A :log-attendance-note must account for EVERY test-taker enrolled to the
;; target session: each roster member must appear in :check-in or :absent.
;; HARD CHECK 6 only requires every NAMED id to be a roster member (subset),
;; HARD CHECK 8 requires the two sets to be disjoint, and HARD CHECK 14 forbids
;; duplicating an id — but none requires FULL coverage. A partial note that
;; omits an enrolled test-taker leaves that person's status undefined in the
;; paper trail, yet auto-commits at Phase 3 as if attendance were complete.
;; HARD CHECK 22 rejects it outright, never held, never auto-committed.

(ns testadmn-attendance-reconcile-test
  (:require [clojure.test :refer [deftest is]]
            [testadmn.store :as store]
            [testadmn.governor :as gov]
            [testadmn.operation :as op]))

(defn- note-proposal [data]
  {:testadmn.proposal/id "p22"
   :testadmn.proposal/type :log-attendance-note
   :testadmn.proposal/effect :propose
   :testadmn.proposal/target-session-id "sess-001"
   :testadmn.proposal/proposal-data data})

(defn- scheduled-store []
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-101"
       :testadmn.test-session/roster #{"s001" "s002" "s003"}})
    s))

(deftest test-hard-check-22-complete-attendance-passes
  ;; Every enrolled test-taker is accounted for (checked-in or absent) and the
  ;; note is otherwise clean: it passes.
  (let [s (scheduled-store)
        result (gov/evaluate-proposal s
                 (note-proposal {:check-in ["s001" "s002"] :absent ["s003"]}))
        check22 (nth (:checks result) 20)]
    (is (true? (:accepted? result)))
    (is (= "all-checks-pass" (:reason result)))
    (is (= "attendance-reconciled" (:reason check22)))))

(deftest test-hard-check-22-incomplete-attendance-rejected
  ;; An enrolled test-taker (s003) is in NEITHER :check-in nor :absent: their
  ;; status is left undefined in the paper trail. Rejected outright.
  (let [s (scheduled-store)
        result (gov/evaluate-proposal s
                 (note-proposal {:check-in ["s001"] :absent ["s002"]}))]
    (is (false? (:accepted? result)))
    (is (= "attendance-incomplete-roster" (:reason result)))
    (is (= ["s003"] (:unaccounted (some #(when (= "attendance-incomplete-roster" (:reason %)) %) (:checks result)))))))

(deftest test-hard-check-22-disjoint-full-coverage-still-passes
  ;; Full coverage achieved by splitting the roster across the two disjoint
  ;; fields is fine (HARD CHECK 8 still requires disjointness).
  (let [s (scheduled-store)
        result (gov/evaluate-proposal s
                 (note-proposal {:check-in ["s001"] :absent ["s002" "s003"]}))]
    (is (true? (:accepted? result)))
    (is (= "all-checks-pass" (:reason result)))))

(deftest test-hard-check-22-guards-only-attendance-op
  ;; HARD CHECK 22 applies only to :log-attendance-note; other ops pass trivially.
  (let [s (scheduled-store)
        proposal {:testadmn.proposal/id "op22"
                  :testadmn.proposal/type :schedule-test-session
                  :testadmn.proposal/effect :propose
                  :testadmn.proposal/target-session-id "sess-001"
                  :testadmn.proposal/proposal-data {:room "Gym A" :proctors 3}}
        result (gov/evaluate-proposal s proposal)
        check22 (nth (:checks result) 20)]
    (is (true? (:pass? check22)))
    (is (= "not-an-attendance-note" (:reason check22)))))

(deftest test-incomplete-attendance-never-auto-commits-phase3
  ;; At Phase 3 an incomplete attendance note must be rejected, never
  ;; auto-committed.
  (let [s (scheduled-store)
        operation (op/make-operation :log-attendance-note "sess-001"
                                     {:check-in ["s001"] :absent ["s002"]})
        result (op/execute-operation operation s 3)]
    (is (= :rejected (:status result)))
    (is (= "attendance-incomplete-roster" (:reason result)))))

(deftest test-check8-still-last-after-check22
  ;; HARD CHECK 8 stays the LAST entry of the :checks vector so
  ;; testadmn_integrity_test's (last :checks) contract keeps holding.
  (let [s (scheduled-store)
        result (gov/evaluate-proposal s
                 (note-proposal {:check-in ["s001" "s002"] :absent ["s003"]}))
        check8 (last (:checks result))]
    (is (= "attendance-non-contradictory" (:reason check8)))))
