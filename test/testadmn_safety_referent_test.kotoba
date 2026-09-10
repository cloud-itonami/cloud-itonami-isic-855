;; testadmn_safety_referent_test -- HARD CHECK 17: safety-concern referent
;;
;; A :flag-safety-concern must name a concrete referent (a non-blank
;; :facility-id and/or :test-taker-id) so triage knows WHERE or WHO, not just
;; a recognized CATEGORY. HARD CHECK 11 (safety-concern category) requires a
;; category; HARD CHECK 3 (scope-exclusion) legitimizes every flag carrying
;; flagging keywords, so a category-only flag with no referent used to
;; escalate as a content-free no-op that nobody could route to a room or
;; person. HARD CHECK 17 requires the referent.

(ns testadmn-safety-referent-test
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

(defn- check17-of
  [result]
  ;; checks vector order: [check1..check7 check9..check16 check17 check8]
  (nth (:checks result) 15))

(deftest test-hard-check-17-referenced-safety-flag-passes
  ;; A flag naming a facility-id referent still escalates.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [result (gov/evaluate-proposal
                   s
                   (flag-proposal
                     {:concern "proctor observed possible integrity incident at station 5"
                      :safety-concerns [:integrity-incident]
                      :facility-id "station-5"}))]
      (is (true? (:accepted? result)))
      (is (= "flag-safety-concern-escalates" (:reason result)))
      (is (= "safety-concern-referenced" (:reason (check17-of result)))))))

(deftest test-hard-check-17-test-taker-referent-passes
  ;; A flag naming a test-taker-id referent is also triageable.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [result (gov/evaluate-proposal
                   s
                   (flag-proposal
                     {:concern "proctor observed possible wellbeing concern"
                      :safety-concerns [:test-taker-wellbeing]
                      :test-taker-id "T-100"}))]
      (is (true? (:accepted? result)))
      (is (= "safety-concern-referenced" (:reason (check17-of result)))))))

(deftest test-hard-check-17-missing-referent-rejected
  ;; A category with no referent is an untriageable content-free no-op.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [result (gov/evaluate-proposal
                   s
                   (flag-proposal
                     {:concern "proctor observed possible integrity incident"
                      :safety-concerns [:integrity-incident]}))]
      (is (false? (:accepted? result)))
      (is (= "safety-concern-missing-referent" (:reason result))))))

(deftest test-hard-check-17-blank-referent-rejected
  ;; A whitespace-only facility-id is still a no-op, never a real room.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [result (gov/evaluate-proposal
                   s
                   (flag-proposal
                     {:concern "proctor observed possible environmental hazard"
                      :safety-concerns [:environmental-hazard]
                      :facility-id "  "}))]
      (is (false? (:accepted? result)))
      (is (= "safety-concern-missing-referent" (:reason result))))))

(deftest test-hard-check-17-non-flag-op-passes-trivially
  ;; The check only guards :flag-safety-concern; other ops pass trivially.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [proposal {:testadmn.proposal/id "p1"
                    :testadmn.proposal/target-session-id "sess-001"
                    :testadmn.proposal/effect :propose
                    :testadmn.proposal/type :schedule-test-session
                    :testadmn.proposal/proposal-data {:room "Gym A" :proctors 3}}
          check17 (check17-of (gov/evaluate-proposal s proposal))]
      (is (true? (:pass? check17)))
      (is (= "not-a-safety-concern" (:reason check17))))))

(deftest test-referent-less-safety-flag-never-escalates
  ;; End to end: at Phase 2 a referent-less flag is rejected, never escalated.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [operation (op/make-operation :flag-safety-concern "sess-001"
                                       {:concern "proctor observed possible integrity incident"
                                        :safety-concerns [:integrity-incident]})
          result (op/execute-operation operation s 2)]
      (is (= :rejected (:status result)))
      (is (= "safety-concern-missing-referent" (:reason result))))))