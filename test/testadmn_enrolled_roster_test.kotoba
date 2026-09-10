;; testadmn_enrolled_roster_test — HARD CHECK 18: schedule enrolled roster
;;
;; A :schedule-test-session must carry a NON-EMPTY enrolled test-taker roster
;; on its target session. ISIC-855 test administration cannot run an exam
;; nobody is registered to sit. HARD CHECK 6 (enrollment binding) only
;; constrains attendance/accommodation proposals that NAME test-takers; a
;; :schedule-test-session names none, so a roster-less session passes checks
;; 1-17 and, at Phase 3, auto-commits a plan to run an exam nobody is
;; enrolled to sit. That is the scheduling-side analog of ghost/proxy
;; testing. This check rejects it outright.

(ns testadmn-enrolled-roster-test
  (:require [clojure.test :refer [deftest is]]
            [testadmn.store :as store]
            [testadmn.governor :as gov]
            [testadmn.operation :as op]))

(defn- schedule-proposal [session-id]
  {:testadmn.proposal/id "er-1"
   :testadmn.proposal/type :schedule-test-session
   :testadmn.proposal/effect :propose
   :testadmn.proposal/target-session-id session-id
   :testadmn.proposal/proposal-data {:room "Gym A" :proctors 3}})

(deftest test-schedule-roster-less-session-rejected
  ;; A registered, venued, start-ed, staffed session with NO enrolled roster
  ;; is still not schedulable: you cannot plan to administer an exam that
  ;; nobody is registered to sit. Rejected outright.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/name "SAT Administration 2026-07-15"
       :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-101"})
    (let [result (gov/evaluate-proposal s (schedule-proposal "sess-001"))]
      (is (false? (:accepted? result)))
      (is (= "schedule-no-enrolled-roster" (:reason result))))))

(deftest test-schedule-session-with-roster-accepted
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/name "SAT Administration 2026-07-15"
       :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-101"
       :testadmn.test-session/roster #{"s001" "s002" "s003"}})
    (let [result (gov/evaluate-proposal s (schedule-proposal "sess-001"))]
      (is (true? (:accepted? result)))
      (is (= "all-checks-pass" (:reason result))))))

(deftest test-schedule-only-guards-schedule-op
  ;; HARD CHECK 18 applies only to :schedule-test-session. A non-scheduling
  ;; op (e.g. flag-safety-concern) must pass it trivially even against a
  ;; roster-less session — the check is about scheduling logistics, not about
  ;; blocking safety reporting.
  (let [s (store/new-mem-store)
        proposal {:testadmn.proposal/id "er-2"
                  :testadmn.proposal/type :flag-safety-concern
                  :testadmn.proposal/effect :propose
                  :testadmn.proposal/target-session-id "n/a"
                  :testadmn.proposal/proposal-data
                    {:concern "proctor observed possible integrity issue at station 5"
                     :safety-concerns [:integrity-incident]
                     :facility-id "station-5"}}
        result (gov/evaluate-proposal s proposal)
        check18 (nth (:checks result) 16)]
    (is (true? (:pass? check18)))
    (is (= "not-a-schedule" (:reason check18)))))

(deftest test-roster-less-schedule-never-auto-commits-phase3
  ;; The logistics point of HARD CHECK 18: checks 1, 9, 12 all pass (exists,
  ;; registered, proctors>=1, venue+start present) yet a roster-less session
  ;; must never auto-commit at Phase 3. Driving through execute-operation
  ;; proves the rejection path.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/name "SAT Administration 2026-07-15"
       :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-101"})
    (let [operation (op/make-operation :schedule-test-session "sess-001"
                                       {:room "Gym A" :proctors 3})
          result (op/execute-operation operation s 3)]
      (is (= :rejected (:status result)))
      (is (= "schedule-no-enrolled-roster" (:reason result))))))