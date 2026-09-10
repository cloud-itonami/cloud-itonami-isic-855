;; testadmn_safety_category_test -- HARD CHECK 11: safety-concern category
;;
;; A :flag-safety-concern must name WHAT the concern is about (a facility hazard,
;; a test-taker wellbeing issue, a suspected integrity incident, an
;; environmental hazard). HARD CHECK 3 (scope-exclusion) only legitimizes safety
;; flags -- the type keyword itself carries flagging keywords, so an empty
;; "no actual concern" used to pass scope-exclusion and escalate as a
;; content-free no-op. HARD CHECK 11 requires a declared, recognized category.

(ns testadmn-safety-category-test
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

(defn- check11-of
  [result]
  ;; checks vector order: [check1..check7 check9 check10 check11 check8]
  (nth (:checks result) 9))

(deftest test-hard-check-11-categorized-safety-flag-passes
  ;; A flag naming a recognized category still escalates.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [result (gov/evaluate-proposal
                   s
                   (flag-proposal
                     {:concern "proctor observed possible integrity issue at station 5"
                      :safety-concerns [:integrity-incident] :facility-id "station-5"}))]
      (is (true? (:accepted? result)))
      (is (= "flag-safety-concern-escalates" (:reason result)))
      (is (= "safety-concern-categorized" (:reason (check11-of result)))))))

(deftest test-hard-check-11-missing-category-rejected
  ;; No declared concern => content-free flag rejected outright.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [result (gov/evaluate-proposal
                   s
                   (flag-proposal
                     {:concern "proctor observed integrity issue"}))]
      (is (false? (:accepted? result)))
      (is (= "safety-concern-missing-category" (:reason result))))))

(deftest test-hard-check-11-unknown-category-rejected
  ;; A category outside the closed set rejected outright, never held.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [result (gov/evaluate-proposal
                   s
                   (flag-proposal
                     {:concern "proctor observed possible integrity issue"
                      :safety-concerns [:proctor-lanyards]}))]
      (is (false? (:accepted? result)))
      (is (= "safety-concern-unknown-category" (:reason result))))))

(deftest test-hard-check-11-non-flag-op-passes-trivially
  ;; The check only guards :flag-safety-concern; other ops pass trivially.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [proposal {:testadmn.proposal/id "p1"
                    :testadmn.proposal/target-session-id "sess-001"
                    :testadmn.proposal/effect :propose
                    :testadmn.proposal/type :schedule-test-session
                    :testadmn.proposal/proposal-data {:room "Gym A" :proctors 3}}
          check11 (check11-of (gov/evaluate-proposal s proposal))]
      (is (true? (:pass? check11)))
      (is (= "not-a-safety-concern" (:reason check11))))))

(deftest test-uncategorized-safety-flag-never-escalates
  ;; End to end: at Phase 2 a category-less flag is rejected, never escalated.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [operation (op/make-operation :flag-safety-concern "sess-001"
                                       {:concern "proctor observed integrity issue"})
          result (op/execute-operation operation s 2)]
      (is (= :rejected (:status result)))
      (is (= "safety-concern-missing-category" (:reason result))))))