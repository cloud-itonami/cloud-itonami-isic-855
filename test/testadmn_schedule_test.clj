;; testadmn_schedule_test — HARD CHECK 12: schedule-verified
;; A :schedule-test-session must reference a session that actually carries a
;; concrete venue (:testadmn.test-session/facility-id) and a start time
;; (:testadmn.test-session/scheduled-start). Scheduling is the Phase-1 act the
;; allowlist auto-commits at Phase 3, so an unschedulable session (no room, no
;; time) must be rejected outright — never auto-committed as if a logistics
;; plan existed.

(ns testadmn-schedule-test
  (:require [clojure.test :refer [deftest is]]
            [testadmn.store :as store]
            [testadmn.governor :as gov]
            [testadmn.operation :as op]))

(defn- schedule-proposal [session-id]
  {:testadmn.proposal/id "sch-1"
   :testadmn.proposal/type :schedule-test-session
   :testadmn.proposal/effect :propose
   :testadmn.proposal/target-session-id session-id
   :testadmn.proposal/proposal-data {:room "Gym A" :proctors 3}})

(deftest test-schedule-missing-facility-rejected
  ;; A registered session with a start time but NO facility is not schedulable:
  ;; you cannot place an exam in a room you never name. Rejected outright.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/name "SAT Administration 2026-07-15"
       :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"})
    (let [result (gov/evaluate-proposal s (schedule-proposal "sess-001"))]
      (is (false? (:accepted? result)))
      (is (= "schedule-missing-facility" (:reason result))))))

(deftest test-schedule-missing-start-rejected
  ;; A registered session with a facility but NO start time is not schedulable:
  ;; you cannot coordinate an exam you never set a time for. Rejected outright.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/name "SAT Administration 2026-07-15"
       :testadmn.test-session/facility-id "facility-101"})
    (let [result (gov/evaluate-proposal s (schedule-proposal "sess-001"))]
      (is (false? (:accepted? result)))
      (is (= "schedule-missing-start" (:reason result))))))

(deftest test-schedule-verified-accepted
  ;; A session with both venue and start time IS schedulable and passes.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/name "SAT Administration 2026-07-15"
       :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-101"
       :testadmn.test-session/roster #{"s001" "s002"}})
    (let [result (gov/evaluate-proposal s (schedule-proposal "sess-001"))]
      (is (true? (:accepted? result)))
      (is (= "all-checks-pass" (:reason result))))))

(deftest test-schedule-check-only-guards-schedule-op
  ;; HARD CHECK 12 applies only to :schedule-test-session. A non-scheduling op
  ;; (e.g. flag-safety-concern) must pass the schedule check trivially even
  ;; when the task names a venue-less session — the check is about scheduling
  ;; logistics, not about blocking safety reporting.
  (let [s (store/new-mem-store)
        proposal {:testadmn.proposal/id "sc-2"
                  :testadmn.proposal/type :flag-safety-concern
                  :testadmn.proposal/effect :propose
                  :testadmn.proposal/target-session-id "n/a"
                  :testadmn.proposal/proposal-data
                    {:concern "proctor observed possible integrity issue at station 5"}}
        result (gov/evaluate-proposal s proposal)
        ;; check 12 itself must pass trivially; the overall accept is governed
        ;; by the safety-escalation flow (flag-safety-concern with flagging
        ;; keywords).
        check12 (nth (:checks result) 10)]
    (is (true? (:pass? check12)))
    (is (= "not-a-schedule" (:reason check12)))))

(deftest test-schedule-missing-session-rejected-by-check-1
  ;; A venue-less/start-less schedule for a completely absent session is caught
  ;; by HARD CHECK 1 (session-not-found) before check 12 ever sees a record.
  (let [s (store/new-mem-store)
        result (gov/evaluate-proposal s (schedule-proposal "absent-999"))]
    (is (false? (:accepted? result)))
    (is (= "session-not-found" (:reason result)))))

(deftest test-schedule-missing-facility-rejected-at-phase-3
  ;; The logistics point of HARD CHECK 12: check 1 passes (session exists and is
  ;; registered) yet an unschedulable session must still never auto-commit at
  ;; Phase 3. Driving through execute-operation proves the hold/rollback path
  ;; rather than just the evaluate callback.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/name "SAT Administration 2026-07-15"
       :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"})
    (let [operation (op/make-operation :schedule-test-session "sess-001"
                                       {:room "Gym A" :proctors 3})
          result (op/execute-operation operation s 3)]
      (is (= :rejected (:status result)))
      (is (= "schedule-missing-facility" (:reason result))))))