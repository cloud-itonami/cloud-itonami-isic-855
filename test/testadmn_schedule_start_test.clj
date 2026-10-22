;; testadmn_schedule_start_test — HARD CHECK 9: schedule-start must be a
;; concrete ISO-8601 date-time.
;; A :schedule-test-session must reference a session whose :scheduled-start is
;; a well-formed ISO-8601 date-time (e.g. "2026-07-15T09:00:00Z"). Scheduling
;; is the Phase-1 act the allowlist auto-commits at Phase 3, so a session whose
;; start is blank, or is a string that is not a parseable timestamp ("09:00",
;; "tomorrow", "ASAP"), must be rejected outright — never auto-committed as if
;; a logistics plan existed.

(ns testadmn-schedule-start-test
  (:require [clojure.test :refer [deftest is]]
            [testadmn.store :as store]
            [testadmn.governor :as gov]
            [testadmn.operation :as op]))

(defn- schedule-proposal [session-id]
  {:testadmn.proposal/id "sch9-1"
   :testadmn.proposal/type :schedule-test-session
   :testadmn.proposal/effect :propose
   :testadmn.proposal/target-session-id session-id
   :testadmn.proposal/proposal-data {:room "Gym A" :proctors 3}})

(defn- register-session-with-start! [session-id start]
  (let [s (store/new-mem-store)]
    (store/register-session!
      s session-id
      (cond-> {}
        (not (nil? start)) (assoc :testadmn.test-session/scheduled-start start)))
    s))

(deftest test-schedule-start-iso-8601-accepted
  ;; A concrete RFC3339 timestamp with a Z suffix is a real time you can place
  ;; an exam at — accepted.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/name "SAT Administration 2026-07-15"
       :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"})
    (let [result (gov/evaluate-proposal s (schedule-proposal "sess-001"))]
      (is (true? (:accepted? result))))))

(deftest test-schedule-start-offset-accepted
  ;; An ISO-8601 timestamp carrying a +09:00 offset is equally concrete.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/name "SAT Administration 2026-07-15"
       :testadmn.test-session/scheduled-start "2026-07-15T18:00:00+09:00"})
    (let [result (gov/evaluate-proposal s (schedule-proposal "sess-001"))]
      (is (true? (:accepted? result))))))

(deftest test-schedule-start-invalid-format-rejected
  ;; "09:00" is not a date — only a clock. It cannot place an exam on a day,
  ;; so it must be rejected outright, never auto-committed at Phase 3.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/name "ACT Administration 2026-07-22"
       :testadmn.test-session/scheduled-start "09:00"})
    (let [result (gov/evaluate-proposal s (schedule-proposal "sess-001"))]
      (is (false? (:accepted? result)))
      (is (= "schedule-start-not-concrete-timestamp" (:reason result))))))

(deftest test-schedule-start-natural-language-rejected
  ;; "tomorrow" reads like a plan, but is not a Unix-placeable time — reject.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/name "AP Chemistry Administration 2026-08-03"
       :testadmn.test-session/scheduled-start "tomorrow"})
    (let [result (gov/evaluate-proposal s (schedule-proposal "sess-001"))]
      (is (false? (:accepted? result)))
      (is (= "schedule-start-not-concrete-timestamp" (:reason result))))))

(deftest test-schedule-start-missing-rejected
  ;; A session with no start at all is not schedulable — a schedule plan with
  ;; no time is not a logistics plan. Rejected outright.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/name "TOEFL Administration 2026-08-10"})
    (let [result (gov/evaluate-proposal s (schedule-proposal "sess-001"))]
      (is (false? (:accepted? result)))
      (is (= "schedule-start-not-concrete-timestamp" (:reason result))))))

(deftest test-schedule-start-does-not-affect-non-schedule-ops
  ;; Check 9 applies only to :schedule-test-session — an attendance note for a
  ;; start-less session must not be rejected by THIS check (attendance guards
  ;; are checks 6 and 8; check 9 is scoped to scheduling).
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/name "IELTS Administration 2026-08-17"
       :testadmn.test-session/scheduled-start "09:00"})
    (let [proposal {:testadmn.proposal/id "att-9-1"
                    :testadmn.proposal/type :log-attendance-note
                    :testadmn.proposal/effect :propose
                    :testadmn.proposal/target-session-id "sess-001"
                    :testadmn.proposal/proposal-data {:check-in ["T. Nakagawa"]}}]
      (let [result (gov/evaluate-proposal s proposal)]
        (is (true? (:accepted? result)))))))