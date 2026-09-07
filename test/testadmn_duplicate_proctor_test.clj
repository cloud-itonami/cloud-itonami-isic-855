;; testadmn_duplicate_proctor_test — HARD CHECK 13: no duplicate proctor assignment
;;
;; A :coordinate-proctor-assignment-proposal must not name the same proctor id
;; more than once. HARD CHECK 10 (non-empty) only requires that SOMEONE is
;; named, and HARD CHECK 4 (impartiality) only rejects declared-conflicted
;; proctors; neither guards against the SAME proctor being doubled in the
;; paper trail. Doubling an id fabricates a second proctor in the room — it
;; inflates the observable staffing roster without adding an actual body.
;; HARD CHECK 13 rejects it outright, never held, never auto-committed at
;; Phase 3.

(ns testadmn-duplicate-proctor-test
  (:require [clojure.test :refer :all]
            [testadmn.store :as store]
            [testadmn.governor :as gov]
            [testadmn.operation :as op]))

(deftest test-hard-check-13-distinct-proctors-pass
  ;; A proctor-assignment naming distinct, explicitly-impartial proctors is
  ;; accepted (checks 4, 10 and 13 all pass). The session is registered with
  ;; a facility/start so HARD CHECK 12 (schedule-verified) never interferes.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/facility-id "f1"
       :testadmn.test-session/scheduled-start "2026-01-01T09:00:00Z"})
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

(deftest test-hard-check-13-duplicate-proctor-rejected
  ;; Naming the SAME proctor id twice is a fabricated second proctor: rejected
  ;; outright, never auto-committed at Phase 3.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/facility-id "f1"
       :testadmn.test-session/scheduled-start "2026-01-01T09:00:00Z"})
    (let [proposal {:testadmn.proposal/id "p1"
                    :testadmn.proposal/target-session-id "sess-001"
                    :testadmn.proposal/effect :propose
                    :testadmn.proposal/type :coordinate-proctor-assignment-proposal
                    :testadmn.proposal/proposal-data
                    {:proctors [{:proctor/id "A. Yamada"
                                 :testadmn.proposal/proctor-impartial? true}
                                {:proctor/id "A. Yamada"
                                 :testadmn.proposal/proctor-impartial? true}]}}
          result (gov/evaluate-proposal s proposal)]
      (is (false? (:accepted? result)))
      (is (= "proctor-assignment-duplicate" (:reason result))))))

(deftest test-hard-check-13-non-proctor-op-passes-trivially
  ;; The check only guards :coordinate-proctor-assignment-proposal; other ops
  ;; (e.g. :schedule-test-session) pass check 13 trivially. The session carries
  ;; facility/start so HARD CHECK 12 does not itself reject the schedule.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/facility-id "f1"
       :testadmn.test-session/scheduled-start "2026-01-01T09:00:00Z"})
    (let [proposal {:testadmn.proposal/id "p1"
                    :testadmn.proposal/target-session-id "sess-001"
                    :testadmn.proposal/effect :propose
                    :testadmn.proposal/type :schedule-test-session
                    :testadmn.proposal/proposal-data {:room "Gym A" :proctors 3}}
          result (gov/evaluate-proposal s proposal)
          check13 (nth (:checks result) 11)]
      (is (true? (:accepted? result)))
      (is (true? (:pass? check13)))
      (is (= "not-a-proctor-assignment" (:reason check13))))))

(deftest test-duplicate-proctor-never-auto-commits-phase3
  ;; End to end: at Phase 3 a duplicated proctor assignment is rejected by the
  ;; governor and never auto-committed.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/facility-id "f1"
       :testadmn.test-session/scheduled-start "2026-01-01T09:00:00Z"})
    (let [operation (op/make-operation :coordinate-proctor-assignment-proposal "sess-001"
                                        {:proctors [{:proctor/id "A. Yamada"
                                                     :testadmn.proposal/proctor-impartial? true}
                                                    {:proctor/id "A. Yamada"
                                                     :testadmn.proposal/proctor-impartial? true}]})
          result (op/execute-operation operation s 3)]
      (is (= :rejected (:status result)))
      (is (= "proctor-assignment-duplicate" (:reason result))))))

(deftest test-check8-still-last-after-check13
  ;; HARD CHECK 8 (attendance self-contradiction) stays the LAST entry of the
  ;; governor's :checks vector, so testadmn_integrity_test's (last :checks)
  ;; contract keeps holding after HARD CHECK 13 is added.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/roster #{"s001" "s002"}})
    (let [result (gov/evaluate-proposal s
                  {:testadmn.proposal/id "p1"
                   :testadmn.proposal/target-session-id "sess-001"
                   :testadmn.proposal/effect :propose
                   :testadmn.proposal/type :log-attendance-note
                   :testadmn.proposal/proposal-data
                   {:check-in ["s001"] :absent ["s002"]}})
          check8 (last (:checks result))]
      (is (= "attendance-non-contradictory" (:reason check8))))))