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
    (store/register-session! s "sess-001"
      {:testadmn.test-session/name "SAT Administration 2026-07-15"
       :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-101"})
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
    (store/register-session! s "sess-001"
      {:testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-101"})
    (let [operation (op/make-operation :schedule-test-session "sess-001"
                                        {:room "Gym A"})
          result (op/execute-operation operation s 1)]
      (is (= :held-for-approval (:status result))))))

(deftest test-operation-execution-phase-3-auto-commit
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-101"})
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

;; === Closed allowlist coverage: the 3 untested logistics ops ===
;; The allowlist (operation.cljc) declares 5 :propose-only ops, but operation
;; execution tests only exercised :schedule-test-session and
;; :flag-safety-concern. The remaining core ISIC-855 test-administration
;; logistics ops — proctor assignment proposal, supply coordination, and
;; attendance logging — were never driven through execute-operation.
;; These fill that gap so the closed allowlist contract is verified end to end.

(deftest test-proctor-assignment-proposal-phase2-held
  ;; Phase 2 gates :coordinate-proctor-assignment-proposal (approval-gated).
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [operation (op/make-operation :coordinate-proctor-assignment-proposal "sess-001"
                                        {:proctors ["A. Yamada" "B. Sato"]
                                         :rooms ["Gym A" "Room 2"]})
          result (op/execute-operation operation s 2)]
      (is (= :held-for-approval (:status result))))))

(deftest test-proctor-assignment-proposal-phase1-blocked
  ;; Phase 1 has not yet unlocked the proctor-assignment op.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [operation (op/make-operation :coordinate-proctor-assignment-proposal "sess-001"
                                        {:proctors ["A. Yamada"]})
          result (op/execute-operation operation s 1)]
      (is (= :rejected (:status result)))
      (is (= "operation-not-allowed-in-phase" (:reason result))))))

(deftest test-proctor-assignment-auto-commits-phase3
  ;; Phase 3 auto-commits clean proposals for this logistics op.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [operation (op/make-operation :coordinate-proctor-assignment-proposal "sess-001"
                                        {:proctors ["A. Yamada" "B. Sato"]})
          result (op/execute-operation operation s 3)]
      (is (= :auto-committed (:status result))))))

(deftest test-supply-request-phase2-held
  ;; :coordinate-supply-request (non-content consumables) is gated at Phase 2.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [operation (op/make-operation :coordinate-supply-request "sess-001"
                                        {:supplies ["answer-sheets" "pencils" "scratch-paper"]})
          result (op/execute-operation operation s 2)]
      (is (= :held-for-approval (:status result))))))

(deftest test-supply-request-scope-excludes-content
  ;; The governor must still block content-bearing supply requests: ordering
  ;; test booklets (test-content) with the supply op must not sneak through.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [operation (op/make-operation :coordinate-supply-request "sess-001"
                                        {:supplies ["test-content" "booklets"]})
          result (op/execute-operation operation s 2)]
      (is (= :rejected (:status result)))
      (is (= "scope-excluded" (:reason result))))))

(deftest test-attendance-note-phase3-auto-commit
  ;; :log-attendance-note is only unlocked at Phase 3 and auto-commits -- but
  ;; only test-takers enrolled on the session roster (HARD CHECK 6).
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/roster #{"s001" "s002" "s003"}})
    (let [operation (op/make-operation :log-attendance-note "sess-001"
                                        {:check-in ["s001" "s002"] :absent ["s003"]})
          result (op/execute-operation operation s 3)]
      (is (= :auto-committed (:status result))))))

(deftest test-attendance-note-phase2-blocked
  ;; Attendance logging must not be allowed before Phase 3.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [operation (op/make-operation :log-attendance-note "sess-001"
                                        {:check-in ["s001"]})
          result (op/execute-operation operation s 2)]
      (is (= :rejected (:status result)))
      (is (= "operation-not-allowed-in-phase" (:reason result))))))

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

;; === Backend parity (MemStore vs DatomicStore) ===
;; Proves DatomicStore satisfies the SAME TestAdmnStore protocol contract
;; as MemStore -- the same pattern cloud-itonami-isic-7810's
;; employmentops.store-contract-test uses.

(defn- backends []
  [["MemStore" (store/new-mem-store)] ["DatomicStore" (store/new-datomic-store)]])

(deftest datomic-store-contract-test
  (doseq [[label s] (backends)]
    (testing (str label ": register-session! + lookup-session")
      (store/register-session! s "sess-001" {:testadmn.test-session/name "Test Session"})
      (let [session (store/lookup-session s "sess-001")]
        (is (= "sess-001" (:testadmn.test-session/id session)))
        (is (= "Test Session" (:testadmn.test-session/name session)))
        (is (true? (:testadmn.test-session/registered? session)))
        (is (false? (:testadmn.test-session/verified? session)))))
    (testing (str label ": lookup-session on a nonexistent id returns nil")
      (is (nil? (store/lookup-session s "nonexistent"))))
    (testing (str label ": create-session! leaves a session unregistered")
      (store/create-session! s "sess-002" {})
      (let [session (store/lookup-session s "sess-002")]
        (is (some? session))
        (is (false? (:testadmn.test-session/registered? session)))
        (is (false? (:testadmn.test-session/verified? session)))))
    (testing (str label ": log-proposal! + proposal-log is append-only and order-preserving")
      (is (= [] (store/proposal-log s)))
      (store/log-proposal! s {:testadmn.proposal/id "p1" :testadmn.proposal/type :flag-safety-concern})
      (store/log-proposal! s {:testadmn.proposal/id "p2" :testadmn.proposal/type :schedule-test-session})
      (is (= ["p1" "p2"] (mapv :testadmn.proposal/id (store/proposal-log s)))))))

(deftest datomic-store-full-flow-matches-mem-store
  ;; the exact governor + operation flow test-safety-concern-escalates
  ;; exercises against MemStore, re-run against DatomicStore end-to-end.
  (let [s (store/new-datomic-store)]
    (store/register-session! s "sess-001" {})
    (let [operation (op/make-operation :flag-safety-concern "sess-001"
                                        {:concern "proctor observed integrity issue"})
          result (op/execute-operation operation s 2)]
      (is (= :escalated (:status result))))))

;; === HARD CHECK 4: proctor impartiality ===
;; An examiner must never be assigned as proctor for a session in which they
;; teach, supervise, or have a declared relationship with a registered
;; test-taker. This is an exam-integrity control the closed allowlist
;; schedules from Phase 2; because Phase 3 auto-commits clean proctor
;; assignments, a conflicted proctor MUST be rejected by the governor and
;; never auto-committed.

(deftest test-hard-check-4-blocks-declared-conflicted-proctor
  ;; A proctor-assignment proposal naming a proctor who declares
  ;; non-impartiality (teaches/relates to a test-taker of the session) is
  ;; rejected outright — the governor must not let it reach Phase 3
  ;; auto-commit.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [proposal {:testadmn.proposal/id "p1"
                    :testadmn.proposal/target-session-id "sess-001"
                    :testadmn.proposal/effect :propose
                    :testadmn.proposal/type :coordinate-proctor-assignment-proposal
                    :testadmn.proposal/proposal-data
                    {:proctors [{:proctor/id "A. Yamada"
                                 :testadmn.proposal/proctor-impartial? true}
                                {:proctor/id "T. Tanaka"
                                 :testadmn.proposal/proctor-impartial? false}]}}
          result (gov/evaluate-proposal s proposal)]
      (is (false? (:accepted? result)))
      (is (= "proctor-conflict-of-interest" (:reason result))))))

(deftest test-hard-check-4-all-impartial-proctors-pass
  ;; A proctor-assignment proposal is accepted when every named proctor
  ;; declares impartiality explicitly.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [proposal {:testadmn.proposal/id "p1"
                    :testadmn.proposal/target-session-id "sess-001"
                    :testadmn.proposal/effect :propose
                    :testadmn.proposal/type :coordinate-proctor-assignment-proposal
                    :testadmn.proposal/proposal-data
                    {:proctors [{:proctor/id "A. Yamada"
                                 :testadmn.proposal/proctor-impartial? true}
                                {:proctor/id "B. Sato"
                                 :testadmn.proposal/proctor-impartial? true}]}}
          result (gov/evaluate-proposal s proposal)]
      (is (true? (:accepted? result)))
      (is (= "all-checks-pass" (:reason result))))))

(deftest test-hard-check-4-non-proctor-ops-unaffected
  ;; HARD check 4 only governs :coordinate-proctor-assignment-proposal; other
  ;; ops (e.g. :schedule-test-session with a :proctors plan-of-strings, as the
  ;; demo seed uses) pass check4 trivially.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-101"})
    (let [proposal {:testadmn.proposal/id "p1"
                    :testadmn.proposal/target-session-id "sess-001"
                    :testadmn.proposal/effect :propose
                    :testadmn.proposal/type :schedule-test-session
                    :testadmn.proposal/proposal-data {:room "Gym A" :proctors 3}}
          check4 (nth (:checks (gov/evaluate-proposal s proposal)) 3)]
      (is (true? (:pass? check4))))))

(deftest test-conflicted-proctor-never-auto-committed-phase3
  ;; End to end: a conflicted proctor assignment at Phase 3 is rejected by the
  ;; governor and never auto-committed.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [operation (op/make-operation :coordinate-proctor-assignment-proposal "sess-001"
                                        {:proctors [{:proctor/id "A. Yamada"
                                                     :testadmn.proposal/proctor-impartial? true}
                                                    {:proctor/id "T. Tanaka"
                                                     :testadmn.proposal/proctor-impartial? false}]})
          result (op/execute-operation operation s 3)]
      (is (= :rejected (:status result)))
      (is (= "proctor-conflict-of-interest" (:reason result))))))

(deftest test-impartial-proctor-auto-commits-phase3
  ;; All-impartial proctor assignment still auto-commits at Phase 3.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [operation (op/make-operation :coordinate-proctor-assignment-proposal "sess-001"
                                        {:proctors [{:proctor/id "A. Yamada"
                                                     :testadmn.proposal/proctor-impartial? true}
                                                    {:proctor/id "B. Sato"
                                                     :testadmn.proposal/proctor-impartial? true}]})
          result (op/execute-operation operation s 3)]
      (is (= :auto-committed (:status result))))))
;; === Accessibility Accommodation Logistics (op + HARD CHECK 5) ===
;; ISIC-855 test-administration must arrange HOW a test-taker with a disability
;; accesses a session (extra time, reader/scribe, accessible room, alternate
;; format, assistive tech) without changing test content, grading, or
;; eligibility. The allowlist schedules :coordinate-accommodation-logistics
;; from Phase 2 and auto-commits clean proposals at Phase 3, so an invalid
;; (category-less, unknown-category, or content-bearing) accommodation MUST be
;; rejected by the governor rather than auto-committed.

(deftest test-accommodation-op-phase2-held
  ;; Phase 2 gates :coordinate-accommodation-logistics (approval-gated). The
  ;; op names a test-taker who is enrolled on the session roster (HARD 6).
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/roster #{"T. Nakagawa"}})
    (let [operation (op/make-operation :coordinate-accommodation-logistics "sess-001"
                                        {:accommodations [:time-extension :alternate-format]
                                         :test-taker "T. Nakagawa"})
          result (op/execute-operation operation s 2)]
      (is (= :held-for-approval (:status result))))))

(deftest test-accommodation-op-phase1-blocked
  ;; Phase 1 has not yet unlocked the accommodation logistics op.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [operation (op/make-operation :coordinate-accommodation-logistics "sess-001"
                                        {:accommodations [:time-extension]})
          result (op/execute-operation operation s 1)]
      (is (= :rejected (:status result)))
      (is (= "operation-not-allowed-in-phase" (:reason result))))))

(deftest test-accommodation-auto-commits-phase3
  ;; Phase 3 auto-commits clean accommodation logistics proposals: recognized
  ;; categories (HARD 5) AND an enrolled test-taker (HARD 6).
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/roster #{"T. Nakagawa"}})
    (let [operation (op/make-operation :coordinate-accommodation-logistics "sess-001"
                                        {:accommodations [:reader/scribe :accessible-room]
                                         :test-taker "T. Nakagawa"})
          result (op/execute-operation operation s 3)]
      (is (= :auto-committed (:status result))))))

(deftest test-hard-check-5-missing-category-rejected
  ;; A logistics-only op that declares no accommodation category must be
  ;; rejected: auto-commit must not turn a contentless channel into a silent
  ;; access grant with no HOW documented.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [proposal {:testadmn.proposal/id "p1"
                    :testadmn.proposal/target-session-id "sess-001"
                    :testadmn.proposal/effect :propose
                    :testadmn.proposal/type :coordinate-accommodation-logistics
                    :testadmn.proposal/proposal-data {:test-taker "T. Nakagawa"}}
          result (gov/evaluate-proposal s proposal)]
      (is (false? (:accepted? result)))
      (is (= "accommodation-missing-category" (:reason result))))))

(deftest test-hard-check-5-unknown-category-rejected
  ;; A category outside the closed accommodate set must be rejected.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [proposal {:testadmn.proposal/id "p1"
                    :testadmn.proposal/target-session-id "sess-001"
                    :testadmn.proposal/effect :propose
                    :testadmn.proposal/type :coordinate-accommodation-logistics
                    :testadmn.proposal/proposal-data {:accommodations [:custom-extension]}}
          result (gov/evaluate-proposal s proposal)]
      (is (false? (:accepted? result)))
      (is (= "accommodation-unknown-category" (:reason result))))))

(deftest test-hard-check-3-blocks-content-bearing-accommodation
  ;; An accommodation must never alter test content or grading; a payload that
  ;; carries forbidden content terms is rejected outright, never auto-committed
  ;; at Phase 3.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [proposal {:testadmn.proposal/id "p1"
                    :testadmn.proposal/target-session-id "sess-001"
                    :testadmn.proposal/effect :propose
                    :testadmn.proposal/type :coordinate-accommodation-logistics
                    :testadmn.proposal/proposal-data
                    {:accommodations [:time-extension]
                     :note "change the scoring to rubrics"}}
          result (gov/evaluate-proposal s proposal)]
      (is (false? (:accepted? result)))
      (is (= "scope-excluded" (:reason result))))))

(deftest test-hard-check-5-clean-accommodation-passes
  ;; A clean logistics-only accommodation -- recognized category AND an
  ;; enrolled test-taker (HARD 6) -- passes.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/roster #{"T. Nakagawa"}})
    (let [proposal {:testadmn.proposal/id "p1"
                    :testadmn.proposal/target-session-id "sess-001"
                    :testadmn.proposal/effect :propose
                    :testadmn.proposal/type :coordinate-accommodation-logistics
                    :testadmn.proposal/proposal-data
                    {:accommodations [:time-extension :reader/scribe]
                     :test-taker "T. Nakagawa"}}
          result (gov/evaluate-proposal s proposal)]
      (is (true? (:accepted? result)))
      (is (= "all-checks-pass" (:reason result))))))

(deftest test-accommodation-content-never-auto-commits-phase3
  ;; End to end: a content-bearing accommodation at Phase 3 is blocked by the
  ;; governor and never auto-committed.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [operation (op/make-operation :coordinate-accommodation-logistics "sess-001"
                                        {:accommodations [:time-extension]
                                         :note "adjust the grading"})
          result (op/execute-operation operation s 3)]
      (is (= :rejected (:status result)))
      (is (= "scope-excluded" (:reason result))))))
;; === Test-Taker Enrollment Binding (HARD CHECK 6) ===
;; Test administration must log attendance for -- and arrange accommodations
;; for -- only people actually registered to sit THAT session. A roster-less
;; session or a proposal naming an un-enrolled id is rejected outright, never
;; auto-committed at Phase 3 (anti-impersonation / anti-proxy-testing).

(deftest test-hard-check-6-attendance-missing-test-taker-rejected
  ;; An attendance note that names no test-taker id is invalid outright.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/roster #{"s001" "s002"}})
    (let [proposal {:testadmn.proposal/id "p1"
                    :testadmn.proposal/target-session-id "sess-001"
                    :testadmn.proposal/effect :propose
                    :testadmn.proposal/type :log-attendance-note
                    :testadmn.proposal/proposal-data {}}
          result (gov/evaluate-proposal s proposal)]
      (is (false? (:accepted? result)))
      (is (= "attendance-missing-test-taker" (:reason result))))))

(deftest test-hard-check-6-unenrolled-test-taker-rejected
  ;; Naming a test-taker who is NOT on the session roster is the proxy-testing
  ;; hole: a fabricated person logging check-in must be blocked at the governor,
  ;; never reaching Phase 3 auto-commit.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/roster #{"s001" "s002"}})
    (let [proposal {:testadmn.proposal/id "p1"
                    :testadmn.proposal/target-session-id "sess-001"
                    :testadmn.proposal/effect :propose
                    :testadmn.proposal/type :log-attendance-note
                    :testadmn.proposal/proposal-data {:check-in ["s001" "imposter-99"]}}
          result (gov/evaluate-proposal s proposal)]
      (is (false? (:accepted? result)))
      (is (= "test-taker-not-enrolled" (:reason result))))))

(deftest test-hard-check-6-roster-less-session-rejected
  ;; A session that carries no roster has an empty enrolled set, so even a
  ;; clean-looking attendance note fails -- you cannot log anyone without a
  ;; declared, verifiable roster of who is actually registered.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})  ;; no :roster
    (let [proposal {:testadmn.proposal/id "p1"
                    :testadmn.proposal/target-session-id "sess-001"
                    :testadmn.proposal/effect :propose
                    :testadmn.proposal/type :log-attendance-note
                    :testadmn.proposal/proposal-data {:check-in ["s001"]}}
          result (gov/evaluate-proposal s proposal)]
      (is (false? (:accepted? result)))
      (is (= "test-taker-not-enrolled" (:reason result))))))

(deftest test-hard-check-6-all-enrolled-passes
  ;; Clean attendance against a full-covering roster passes HARD CHECK 6.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/roster #{"s001" "s002" "s003"}})
    (let [proposal {:testadmn.proposal/id "p1"
                    :testadmn.proposal/target-session-id "sess-001"
                    :testadmn.proposal/effect :propose
                    :testadmn.proposal/type :log-attendance-note
                    :testadmn.proposal/proposal-data {:check-in ["s001"] :absent ["s002" "s003"]}}
          result (gov/evaluate-proposal s proposal)]
      (is (true? (:accepted? result)))
      (is (= "all-checks-pass" (:reason result))))))

(deftest test-hard-check-6-accommodation-binds-enrolled-test-taker
  ;; An accommodation for an unregistered test-taker is a silent access grant
  ;; to a non-participant and must be rejected: only enrolled roster members
  ;; may receive accessibility logistics.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/roster #{"T. Nakagawa"}})
    (let [bad {:testadmn.proposal/id "p1"
               :testadmn.proposal/target-session-id "sess-001"
               :testadmn.proposal/effect :propose
               :testadmn.proposal/type :coordinate-accommodation-logistics
               :testadmn.proposal/proposal-data
               {:accommodations [:time-extension] :test-taker "X. Forsythe"}}
          good {:testadmn.proposal/id "p2"
                :testadmn.proposal/target-session-id "sess-001"
                :testadmn.proposal/effect :propose
                :testadmn.proposal/type :coordinate-accommodation-logistics
                :testadmn.proposal/proposal-data
                {:accommodations [:time-extension] :test-taker "T. Nakagawa"}}]
      (is (false? (:accepted? (gov/evaluate-proposal s bad))))
      (is (= "test-taker-not-enrolled" (:reason (gov/evaluate-proposal s bad))))
      (is (true? (:accepted? (gov/evaluate-proposal s good)))))))

(deftest test-hard-check-6-non-binding-ops-unaffected
  ;; HARD CHECK 6 only governs attendance and accommodation; scheduling and
  ;; other ops pass the check trivially even on a roster-less session.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-101"})
    (let [proposal {:testadmn.proposal/id "p1"
                    :testadmn.proposal/target-session-id "sess-001"
                    :testadmn.proposal/effect :propose
                    :testadmn.proposal/type :schedule-test-session
                    :testadmn.proposal/proposal-data {:room "Gym A"}}
          check6 (nth (:checks (gov/evaluate-proposal s proposal)) 5)]
      (is (true? (:pass? check6))))))

(deftest test-unenrolled-attendance-never-auto-commits-phase3
  ;; End to end: an attendance note naming an un-enrolled test-taker at Phase 3
  ;; is rejected by the governor and never auto-committed.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/roster #{"s001"}})
    (let [operation (op/make-operation :log-attendance-note "sess-001"
                                        {:check-in ["s001" "imposter-99"]})
          result (op/execute-operation operation s 3)]
      (is (= :rejected (:status result)))
      (is (= "test-taker-not-enrolled" (:reason result))))))

(deftest test-enrolled-attendance-auto-commits-phase3
  ;; Fully-enrolled attendance still auto-commits at Phase 3.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/roster #{"s001" "s002"}})
    (let [operation (op/make-operation :log-attendance-note "sess-001"
                                        {:check-in ["s001"] :absent ["s002"]})
          result (op/execute-operation operation s 3)]
      (is (= :auto-committed (:status result))))))

;; === Bounded Supply-Consumable Allowlist (HARD CHECK 7) ===
;; The allowlist schedules :coordinate-supply-request from Phase 2 and
;; auto-commits clean proposals at Phase 3, but generic scope-exclusion
;; (HARD CHECK 3) only blocks content-bearing TERMS — it does not bound WHICH
;; non-content consumables a request may order. An unrecognized or empty
;; supply request must be rejected by the governor rather than auto-committed
;; at Phase 3.

(deftest test-hard-check-7-supply-missing-items-rejected
  ;; A supply request naming no items is invalid outright.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [proposal {:testadmn.proposal/id "p1"
                    :testadmn.proposal/target-session-id "sess-001"
                    :testadmn.proposal/effect :propose
                    :testadmn.proposal/type :coordinate-supply-request
                    :testadmn.proposal/proposal-data {}}
          result (gov/evaluate-proposal s proposal)]
      (is (false? (:accepted? result)))
      (is (= "supply-missing-items" (:reason result))))))

(deftest test-hard-check-7-supply-unrecognized-consumable-rejected
  ;; An item outside the closed consumable set (no content-bearing term, so
  ;; generic scope-exclusion lets it through) must be rejected outright.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [proposal {:testadmn.proposal/id "p1"
                    :testadmn.proposal/target-session-id "sess-001"
                    :testadmn.proposal/effect :propose
                    :testadmn.proposal/type :coordinate-supply-request
                    :testadmn.proposal/proposal-data
                    {:supplies ["answer-sheets" "surveillance-cameras"]}}
          result (gov/evaluate-proposal s proposal)]
      (is (false? (:accepted? result)))
      (is (= "supply-unrecognized-consumable" (:reason result))))))

(deftest test-hard-check-7-clean-supply-passes
  ;; Only recognized non-content consumables pass HARD CHECK 7.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [proposal {:testadmn.proposal/id "p1"
                    :testadmn.proposal/target-session-id "sess-001"
                    :testadmn.proposal/effect :propose
                    :testadmn.proposal/type :coordinate-supply-request
                    :testadmn.proposal/proposal-data
                    {:supplies ["answer-sheets" "pencils" "scratch-paper"]}}
          result (gov/evaluate-proposal s proposal)]
      (is (true? (:accepted? result)))
      (is (= "all-checks-pass" (:reason result))))))

(deftest test-hard-check-7-non-supply-ops-unaffected
  ;; HARD CHECK 7 only governs :coordinate-supply-request; others pass trivially.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-101"})
    (let [proposal {:testadmn.proposal/id "p1"
                    :testadmn.proposal/target-session-id "sess-001"
                    :testadmn.proposal/effect :propose
                    :testadmn.proposal/type :schedule-test-session
                    :testadmn.proposal/proposal-data {:room "Gym A"}}
          check6 (nth (:checks (gov/evaluate-proposal s proposal)) 6)]
      (is (true? (:pass? check6))))))

(deftest test-unrecognized-supply-never-auto-commits-phase3
  ;; End to end: a Phase 3 supply request naming an unrecognized consumable is
  ;; rejected and never auto-committed.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [operation (op/make-operation :coordinate-supply-request "sess-001"
                                        {:supplies ["answer-sheets" "smuggled-device"]})
          result (op/execute-operation operation s 3)]
      (is (= :rejected (:status result)))
      (is (= "supply-unrecognized-consumable" (:reason result))))))

(deftest test-clean-supply-auto-commits-phase3
  ;; A supply request naming only recognized consumables still auto-commits.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [operation (op/make-operation :coordinate-supply-request "sess-001"
                                        {:supplies ["answer-sheets" "pencils"]})
          result (op/execute-operation operation s 3)]
      (is (= :auto-committed (:status result))))))