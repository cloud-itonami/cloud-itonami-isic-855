;; testadmn_proctor_staffing_test — HARD CHECK 9: proctor staffing sufficiency.
;;
;; A :schedule-test-session must declare a positive proctor headcount (>=1) in
;; its proposal-data. ISIC-855 standardized-test administration cannot actually
;; run a session with zero proctors: no one to verify attendance, supervise
;; test-takers, or distribute/collect supplies. HARD CHECK 4 (proctor
;; impartiality) only ever guards the SEPARATE :coordinate-proctor-assignment
;; op against conflicted proctors — it says nothing about whether a scheduled
;; session is staffed at all. Today a :schedule-test-session whose
;; :proposal-data names :proctors 0 (or omits it) passes checks 1-8 and, at
;; Phase 3, auto-commits an exam plan with no supervision. HARD CHECK 9 closes
;; that: the schedule must name a positive integer headcount, otherwise it is
;; rejected outright — never held, never auto-committed at Phase 3.

(ns testadmn-proctor-staffing-test
  (:require [clojure.test :refer [deftest is]]
            [testadmn.store :as store]
            [testadmn.governor :as gov]
            [testadmn.operation :as op]))

(defn- schedule-proposal [proposal-data]
  {:testadmn.proposal/id "staff-1"
   :testadmn.proposal/type :schedule-test-session
   :testadmn.proposal/effect :propose
   :testadmn.proposal/target-session-id "sess-001"
   :testadmn.proposal/proposal-data proposal-data})

(defn- staffing-check [result]
  ;; the one HARD-band result whose reason identifies the staffing decision
  (->> (:checks result)
       (filter #(contains? #{"schedule-proctor-staffed"
                             "schedule-no-proctor-staffing"} (:reason %)))
       first))

(deftest test-proctor-staffing-positive-headcount-passes
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [result (gov/evaluate-proposal s
                  (schedule-proposal {:room "Gym A" :proctors 3}))]
      (is (true? (:accepted? result)))
      (is (= "all-checks-pass" (:reason result)))
      (is (true? (:pass? (staffing-check result)))))))

(deftest test-schedule-zero-proctors-rejected
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [result (gov/evaluate-proposal s
                  (schedule-proposal {:room "Gym A" :proctors 0}))]
      (is (false? (:accepted? result)))
      (is (= "schedule-no-proctor-staffing" (:reason result))))))

(deftest test-schedule-negative-proctors-rejected
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [result (gov/evaluate-proposal s
                  (schedule-proposal {:room "Gym A" :proctors -1}))]
      (is (false? (:accepted? result)))
      (is (= "schedule-no-proctor-staffing" (:reason result))))))

(deftest test-schedule-proctors-omitted-rejected
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [result (gov/evaluate-proposal s (schedule-proposal {:room "Gym A"}))]
      (is (false? (:accepted? result)))
      (is (= "schedule-no-proctor-staffing" (:reason result))))))

(deftest test-schedule-string-proctors-rejected
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [result (gov/evaluate-proposal s
                  (schedule-proposal {:room "Gym A" :proctors "3"}))]
      (is (false? (:accepted? result)))
      (is (= "schedule-no-proctor-staffing" (:reason result))))))

(deftest test-schedule-proctors-non-schedule-op-unaffected
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/roster #{"T. Nakagawa"}})
    (let [proposal {:testadmn.proposal/id "staff-2"
                    :testadmn.proposal/type :log-attendance-note
                    :testadmn.proposal/effect :propose
                    :testadmn.proposal/target-session-id "sess-001"
                    :testadmn.proposal/proposal-data {:check-in ["T. Nakagawa"]}}
          st (staffing-check (gov/evaluate-proposal s proposal))]
      (is (nil? st)))))

(deftest test-unstaffed-schedule-never-auto-commits-phase3
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [operation (op/make-operation :schedule-test-session "sess-001"
                                        {:room "Gym A"})
          result (op/execute-operation operation s 3)]
      (is (= :rejected (:status result)))
      (is (= "schedule-no-proctor-staffing" (:reason result))))))

(deftest test-staffed-schedule-still-auto-commits-phase3
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [operation (op/make-operation :schedule-test-session "sess-001"
                                        {:room "Gym A" :proctors 3})
          result (op/execute-operation operation s 3)]
      (is (= :auto-committed (:status result))))))