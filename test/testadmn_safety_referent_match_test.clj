;; testadmn_safety_referent_match_test -- HARD CHECK 19: safety-referent category match
;;
;; A safety flag's referent TYPE must match its category. HARD CHECK 11
;; (safety-concern category) requires a recognized category; HARD CHECK 17
;; (safety-concern referent) requires SOME referent -- but neither ties the
;; referent's kind to the category, so a :facility-hazard flagged with only a
;; :test-taker-id (or a :test-taker-wellbeing flagged with only a :facility-id)
;; would still escalate into the wrong triage lane. HARD CHECK 19 binds them.

(ns testadmn-safety-referent-match-test
  (:require [clojure.test :refer :all]
            [testadmn.store :as store]
            [testadmn.governor :as gov]
            [testadmn.operation :as op]))

(defn- flag-proposal
  [data]
  {:testadmn.proposal/id "p1"
   :testadmn.proposal/target-session-id "sess-001"
   :testadmn.proposal/effect :propose
   :testadmn.proposal/type :flag-safety-concern
   :testadmn.proposal/proposal-data data})

(defn- check19-of
  [result]
  ;; checks vector order: [check1..check7 check9..check18 check19 check8]
  (nth (:checks result) 17))

(deftest test-hard-check-19-facility-hazard-with-facility-id-passes
  ;; A room/environment hazard naming its :facility-id routes to the room lane.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [result (gov/evaluate-proposal
                   s
                   (flag-proposal
                     {:concern "ceiling panel loose over row C"
                      :safety-concerns [:facility-hazard]
                      :facility-id "room-4"}))]
      (is (true? (:accepted? result)))
      (is (= "safety-referent-matched" (:reason (check19-of result)))))))

(deftest test-hard-check-19-facility-hazard-with-only-test-taker-id-rejected
  ;; A room-hazard flagged with only a person id is the wrong triage lane.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [result (gov/evaluate-proposal
                   s
                   (flag-proposal
                     {:concern "ceiling panel loose over row C"
                      :safety-concerns [:facility-hazard]
                      :test-taker-id "T-100"}))]
      (is (false? (:accepted? result)))
      (is (= "safety-referent-facility-missing" (:reason result))))))

(deftest test-hard-check-19-wellbeing-with-test-taker-id-passes
  ;; A person concern naming its :test-taker-id routes to the person lane.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [result (gov/evaluate-proposal
                   s
                   (flag-proposal
                     {:concern "test-taker appears unwell"
                      :safety-concerns [:test-taker-wellbeing]
                      :test-taker-id "T-100"}))]
      (is (true? (:accepted? result)))
      (is (= "safety-referent-matched" (:reason (check19-of result)))))))

(deftest test-hard-check-19-wellbeing-with-only-facility-id-rejected
  ;; A person concern flagged with only a room id is the wrong triage lane.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [result (gov/evaluate-proposal
                   s
                   (flag-proposal
                     {:concern "test-taker appears unwell"
                      :safety-concerns [:test-taker-wellbeing]
                      :facility-id "room-4"}))]
      (is (false? (:accepted? result)))
      (is (= "safety-referent-test-taker-missing" (:reason result))))))

(deftest test-hard-check-19-integrity-incident-still-passes
  ;; :integrity-incident is not in either constrained class; a referent
  ;; (still required by check 17) continues to pass check 19.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [result (gov/evaluate-proposal
                   s
                   (flag-proposal
                     {:concern "proctor observed possible integrity issue at station 5"
                      :safety-concerns [:integrity-incident]
                      :facility-id "station-5"}))]
      (is (true? (:accepted? result)))
      (is (= "safety-referent-matched" (:reason (check19-of result)))))))

(deftest test-hard-check-19-non-flag-op-passes-trivially
  ;; The check only guards :flag-safety-concern; other ops pass trivially.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [proposal {:testadmn.proposal/id "p1"
                    :testadmn.proposal/target-session-id "sess-001"
                    :testadmn.proposal/effect :propose
                    :testadmn.proposal/type :schedule-test-session
                    :testadmn.proposal/proposal-data {:room "Gym A" :proctors 3}}
          check19 (check19-of (gov/evaluate-proposal s proposal))]
      (is (true? (:pass? check19)))
      (is (= "not-a-safety-concern" (:reason check19))))))

(deftest test-mismatched-referent-safety-flag-never-escalates
  ;; End to end: at Phase 2 a facility-hazard flagged with only a person id
  ;; is rejected, never escalated.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [operation (op/make-operation :flag-safety-concern "sess-001"
                                       {:concern "ceiling panel loose over row C"
                                        :safety-concerns [:facility-hazard]
                                        :test-taker-id "T-100"})
          result (op/execute-operation operation s 2)]
      (is (= :rejected (:status result)))
      (is (= "safety-referent-facility-missing" (:reason result))))))
