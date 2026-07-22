;; testadmn_test — Test Administration Actor Tests

(ns testadmn-test
  (:require [clojure.test :refer :all]
            [testadmn.store :as store]
            [testadmn.governor :as gov]
            [testadmn.advisor :as advisor]
            [testadmn.operation :as op]
            [testadmn.phase :as phase]
            [testadmn.sim :as sim]))

;; === Store Tests ===

(deftest test-mem-store-creation
  (let [s (store/new-mem-store)]
    (is (not (nil? s)))
    (is (= [] (store/proposal-log s)))))

(deftest test-register-session
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/name "Test Session"})
    (let [session (store/lookup-session s "sess-001")]
      (is (not (nil? session)))
      (is (= "sess-001" (:testadmn.test-session/id session)))
      (is (= true (:testadmn.test-session/registered? session))))))

(deftest test-lookup-nonexistent-session
  (let [s (store/new-mem-store)]
    (is (nil? (store/lookup-session s "nonexistent")))))

;; === Governor Tests ===

(deftest test-hard-check-1-session-must-exist
  (let [s (store/new-mem-store)
        proposal {:testadmn.proposal/id "p1"
                  :testadmn.proposal/target-session-id "unknown"}
        result (gov/evaluate-proposal s proposal)]
    (is (false? (:accepted? result)))
    (is (= "session-not-found" (:reason result)))))

(deftest test-hard-check-1-session-must-be-registered
  (let [s (store/new-mem-store)]
    ;; `create-session!` (unlike `register-session!`) can leave a session
    ;; existing-but-unregistered -- exactly the ground-truth state this
    ;; check exercises.
    (store/create-session! s "sess-001" {})
    (let [proposal {:testadmn.proposal/id "p1"
                    :testadmn.proposal/target-session-id "sess-001"
                    :testadmn.proposal/effect :propose
                    :testadmn.proposal/type :schedule-test-session}
          result (gov/evaluate-proposal s proposal)]
      (is (false? (:accepted? result)))
      (is (= "session-not-registered" (:reason result))))))

(deftest test-hard-check-2-effect-must-be-propose
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [proposal {:testadmn.proposal/id "p1"
                    :testadmn.proposal/target-session-id "sess-001"
                    :testadmn.proposal/effect :commit  ;; wrong effect
                    :testadmn.proposal/type :schedule-test-session}
          result (gov/evaluate-proposal s proposal)]
      (is (false? (:accepted? result)))
      (is (= "effect-not-propose" (:reason result))))))

(deftest test-hard-check-3-scope-exclusion-test-content
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [proposal {:testadmn.proposal/id "p1"
                    :testadmn.proposal/target-session-id "sess-001"
                    :testadmn.proposal/effect :propose
                    :testadmn.proposal/type :schedule-test-session
                    :testadmn.proposal/proposal-data {:test-content "forbidden"}}
          result (gov/evaluate-proposal s proposal)]
      (is (false? (:accepted? result)))
      (is (= "scope-excluded" (:reason result))))))

(deftest test-hard-check-3-scope-exclusion-answer-key
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [proposal {:testadmn.proposal/id "p1"
                    :testadmn.proposal/target-session-id "sess-001"
                    :testadmn.proposal/effect :propose
                    :testadmn.proposal/type :schedule-test-session
                    :testadmn.proposal/proposal-data {:answer-key "secret"}}
          result (gov/evaluate-proposal s proposal)]
      (is (false? (:accepted? result)))
      (is (= "scope-excluded" (:reason result))))))

(deftest test-flag-safety-concern-escapes-scope-block
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [proposal {:testadmn.proposal/id "p1"
                    :testadmn.proposal/target-session-id "sess-001"
                    :testadmn.proposal/effect :propose
                    :testadmn.proposal/type :flag-safety-concern
                    :testadmn.proposal/proposal-data
                    {:concern "proctor observed possible integrity issue"}}
          result (gov/evaluate-proposal s proposal)]
      (is (true? (:accepted? result)))
      (is (= "flag-safety-concern-escalates" (:reason result))))))

(deftest test-valid-proposal-passes-all-checks
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [proposal {:testadmn.proposal/id "p1"
                    :testadmn.proposal/target-session-id "sess-001"
                    :testadmn.proposal/effect :propose
                    :testadmn.proposal/type :schedule-test-session
                    :testadmn.proposal/proposal-data
                    {:room "Gym A" :proctors 3}}
          result (gov/evaluate-proposal s proposal)]
      (is (true? (:accepted? result)))
      (is (= "all-checks-pass" (:reason result))))))

;; === Phase Tests ===

(deftest test-phase-0-read-only
  (is (= #{} (phase/allowed-operations 0)))
  (is (false? (phase/is-allowed? 0 :schedule-test-session))))

(deftest test-phase-1-schedule-only
  (is (contains? (phase/allowed-operations 1) :schedule-test-session))
  (is (false? (phase/is-allowed? 1 :log-attendance-note))))

(deftest test-phase-2-expanded
  (let [allowed (phase/allowed-operations 2)]
    (is (contains? allowed :schedule-test-session))
    (is (contains? allowed :coordinate-proctor-assignment-proposal))
    (is (contains? allowed :coordinate-supply-request))))

(deftest test-phase-3-auto-commit
  (is (true? (phase/should-auto-commit? 3 :schedule-test-session)))
  (is (false? (phase/should-auto-commit? 3 :flag-safety-concern)))
  (is (false? (phase/should-auto-commit? 1 :schedule-test-session))))

;; === Operation Tests ===

(deftest test-operation-execution-phase-0-blocked
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [operation (op/make-operation :schedule-test-session "sess-001"
                                        {:room "Gym A"})
          result (op/execute-operation operation s 0)]
      (is (= :rejected (:status result)))
      (is (= "operation-not-allowed-in-phase" (:reason result))))))

(deftest test-operation-execution-phase-1-held
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [operation (op/make-operation :schedule-test-session "sess-001"
                                        {:room "Gym A"})
          result (op/execute-operation operation s 1)]
      (is (= :held-for-approval (:status result))))))

(deftest test-operation-execution-phase-3-auto-commit
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [operation (op/make-operation :schedule-test-session "sess-001"
                                        {:room "Gym A"})
          result (op/execute-operation operation s 3)]
      (is (= :auto-committed (:status result))))))

(deftest test-safety-concern-escalates
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [operation (op/make-operation :flag-safety-concern "sess-001"
                                        {:concern "proctor observed integrity issue"})
          result (op/execute-operation operation s 2)]
      (is (= :escalated (:status result))))))

;; === Integration Tests ===

(deftest test-sim-runs
  (let [result (sim/run-demo)]
    (is (not (nil? result)))
    (is (contains? result :phase-0))
    (is (contains? result :phase-1))
    (is (contains? result :phase-2))
    (is (contains? result :rejected))
    (is (contains? result :safety))))

(deftest test-sim-phase-0-rejected
  (let [result (sim/run-demo)]
    (is (= :rejected (:status (:phase-0 result))))))

(deftest test-sim-phase-1-held
  (let [result (sim/run-demo)]
    (is (= :held-for-approval (:status (:phase-1 result))))))

(deftest test-sim-scope-exclusion-rejected
  (let [result (sim/run-demo)]
    (is (= :rejected (:status (:rejected result))))))

(deftest test-sim-safety-escalated
  (let [result (sim/run-demo)]
    (is (= :escalated (:status (:safety result))))))
