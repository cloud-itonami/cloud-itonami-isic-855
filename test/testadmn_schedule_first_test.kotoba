;; testadmn_schedule_first_test — HARD CHECK 20: downstream logistics
;; require a scheduled session
;;
;; ISIC-855 test administration performs four downstream logistics acts against
;; an already-SCHEDULED test session: logging attendance, assigning proctors,
;; ordering supplies, and arranging accommodations. HARD CHECK 12
;; (schedule-verified) only makes :schedule-test-session require a venue and
;; start; none of the four downstream ops re-verifies its target is
;; schedulable, and each auto-commits at Phase 3. HARD CHECK 20 requires each
;; downstream op to target a session carrying a concrete venue (:facility-id)
;; and start (:scheduled-start) — rejected outright, never held, never
;; auto-committed at Phase 3.

(ns testadmn-schedule-first-test
  (:require [clojure.test :refer [deftest is]]
            [testadmn.store :as store]
            [testadmn.governor :as gov]
            [testadmn.operation :as op]))

(defn- scheduled-store []
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/facility-id "facility-101"
       :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/roster #{"s001" "s002" "s003"}})
    s))

(defn- unscheduled-store []
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/roster #{"s001" "s002" "s003"}})
    s))

(deftest test-attendance-requires-scheduled-session
  ;; A clean attendance note for a session with no venue or start is rejected.
  (let [s (unscheduled-store)
        result (gov/evaluate-proposal s
                 {:testadmn.proposal/id "p20"
                  :testadmn.proposal/type :log-attendance-note
                  :testadmn.proposal/effect :propose
                  :testadmn.proposal/target-session-id "sess-001"
                  :testadmn.proposal/proposal-data {:check-in ["s001" "s002"] :absent ["s003"]}})]
    (is (false? (:accepted? result)))
    (is (= "downstream-session-missing-facility" (:reason result)))))

(deftest test-supply-requires-scheduled-session
  (let [s (unscheduled-store)
        result (gov/evaluate-proposal s
                 {:testadmn.proposal/id "p20"
                  :testadmn.proposal/type :coordinate-supply-request
                  :testadmn.proposal/effect :propose
                  :testadmn.proposal/target-session-id "sess-001"
                  :testadmn.proposal/proposal-data {:supplies ["answer-sheets" "pencils"]}})]
    (is (false? (:accepted? result)))
    (is (= "downstream-session-missing-facility" (:reason result)))))

(deftest test-proctor-assignment-requires-scheduled-session
  (let [s (unscheduled-store)
        result (gov/evaluate-proposal s
                 {:testadmn.proposal/id "p20"
                  :testadmn.proposal/type :coordinate-proctor-assignment-proposal
                  :testadmn.proposal/effect :propose
                  :testadmn.proposal/target-session-id "sess-001"
                  :testadmn.proposal/proposal-data
                  {:proctors [{:proctor/id "A. Yamada"
                               :testadmn.proposal/proctor-impartial? true}]}})]
    (is (false? (:accepted? result)))
    (is (= "downstream-session-missing-facility" (:reason result)))))

(deftest test-accommodation-requires-scheduled-session
  (let [s (unscheduled-store)
        result (gov/evaluate-proposal s
                 {:testadmn.proposal/id "p20"
                  :testadmn.proposal/type :coordinate-accommodation-logistics
                  :testadmn.proposal/effect :propose
                  :testadmn.proposal/target-session-id "sess-001"
                  :testadmn.proposal/proposal-data
                  {:test-taker "s001" :accommodations [:time-extension]}})]
    (is (false? (:accepted? result)))
    (is (= "downstream-session-missing-facility" (:reason result)))))

(deftest test-scheduled-session-passes-downstream-ops
  ;; Once the session carries a venue and start, a clean attendance note passes.
  (let [s (scheduled-store)
        result (gov/evaluate-proposal s
                 {:testadmn.proposal/id "p20"
                  :testadmn.proposal/type :log-attendance-note
                  :testadmn.proposal/effect :propose
                  :testadmn.proposal/target-session-id "sess-001"
                  :testadmn.proposal/proposal-data {:check-in ["s001" "s002"] :absent ["s003"]}})]
    (is (true? (:accepted? result)))
    (is (= "all-checks-pass" (:reason result)))))

(deftest test-downstream-never-auto-commits-unscheduled-phase3
  ;; At Phase 3 an attendance note for an unscheduled session must be rejected,
  ;; never auto-committed.
  (let [s (unscheduled-store)
        operation (op/make-operation :log-attendance-note "sess-001"
                                     {:check-in ["s001"] :absent ["s002"]})
        result (op/execute-operation operation s 3)]
    (is (= :rejected (:status result)))
    (is (= "downstream-session-missing-facility" (:reason result)))))

(deftest test-check8-still-last-after-check20
  ;; HARD CHECK 8 stays the LAST entry of the governor's :checks vector, so
  ;; testadmn_integrity_test's (last :checks) contract keeps holding.
  (let [s (scheduled-store)
        result (gov/evaluate-proposal s
                 {:testadmn.proposal/id "p20"
                  :testadmn.proposal/type :log-attendance-note
                  :testadmn.proposal/effect :propose
                  :testadmn.proposal/target-session-id "sess-001"
                  :testadmn.proposal/proposal-data {:check-in ["s001"] :absent ["s002"]}})
        check8 (last (:checks result))]
    (is (= "attendance-non-contradictory" (:reason check8)))))
