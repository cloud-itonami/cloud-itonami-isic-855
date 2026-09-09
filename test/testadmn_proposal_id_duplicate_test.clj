;; testadmn_proposal_id_duplicate_test — HARD CHECK 29: no duplicate proposal id.
;;
;; ISIC-855 test administration's audit trail is keyed by
;; :testadmn.proposal/id -- every committed, escalated, held, or rejected
;; operation is one append to store/proposal-log under that id. Reusing an id
;; already on record does not add a real event to the trail; it overwrites the
;; narrative (a falsified resubmission masquerades as the original's record,
;; and surfaces filtering the log by id cannot tell the two apart). HARD
;; CHECK 29 rejects such a proposal outright -- never held, never
;; auto-committed at Phase 3.

(ns testadmn-proposal-id-duplicate-test
  (:require [clojure.test :refer [deftest is]]
            [testadmn.store :as store]
            [testadmn.governor :as gov]
            [testadmn.operation :as op]))

(defn- fresh-store []
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-101"})
    s))

(defn- supply-op [id]
  (assoc (op/make-operation :coordinate-supply-request "sess-001"
                            {:supplies ["answer-sheets" "pencils" "scratch-paper"]})
         :testadmn.proposal/id id))

(deftest test-check29-fresh-id-passes
  ;; A proposal whose id is not on record passes all checks and, at Phase 3,
  ;; auto-commits normally.
  (let [s (fresh-store)
        result (gov/evaluate-proposal s (supply-op "op-fresh-1"))]
    (is (true? (:accepted? result)))
    (is (= "all-checks-pass" (:reason result)))))

(deftest test-check29-reused-id-rejected
  ;; Evaluate a proposal, then resubmit one stamped with the SAME id: the
  ;; second submission is rejected outright with reason proposal-id-duplicate
  ;; -- never held, never auto-committed at Phase 3.
  (let [s (fresh-store)
        first-op (supply-op "op-dup-1")]
    ;; put the first op on record through the real pipeline (evaluate alone
    ;; does not log); the resubmission stamped with the SAME id is the reuse
    (op/execute-operation first-op s 3)
    (let [second-op (supply-op "op-dup-1")
          result (gov/evaluate-proposal s second-op)]
      (is (false? (:accepted? result)))
      (is (= "proposal-id-duplicate" (:reason result))))))

(deftest test-check29-reused-id-not-auto-committed
  ;; The duplicate is not only rejected by the governor: driving it through
  ;; execute-operation at Phase 3 (the auto-commit phase) must NOT commit a
  ;; second audit event under the same id -- the log keeps exactly one record
  ;; for that id.
  (let [s (fresh-store)
        first-op (supply-op "op-dup-2")]
    (op/execute-operation first-op s 3)
    (let [count-before (count (filter #(= "op-dup-2" (get % :testadmn.proposal/id))
                                      (store/proposal-log s)))
          second-op (supply-op "op-dup-2")
          outcome (op/execute-operation second-op s 3)
          count-after (count (filter #(= "op-dup-2" (get % :testadmn.proposal/id))
                                     (store/proposal-log s)))]
      (is (= 1 count-before))
      (is (= :rejected (:status outcome)))
      (is (= "proposal-id-duplicate" (:reason outcome)))
      (is (= 1 count-after)))))

(deftest test-check29-distinct-ids-still-append
  ;; Different ids keep appending normally: two clean proposals each add their
  ;; own record -- the check rejects REUSE, not the audit trail itself.
  (let [s (fresh-store)]
    (op/execute-operation (supply-op "op-a") s 3)
    (op/execute-operation (supply-op "op-b") s 3)
    (is (= #{"op-a" "op-b"}
           (set (map #(get % :testadmn.proposal/id) (store/proposal-log s)))))))

(deftest test-check29-rejection-logged-under-a-distinct-state
  ;; The rejected duplicate still lands in the log (rejections are audit
  ;; events too), but the ORIGINAL record's effect is untouched -- the
  ;; original's :commit is not overwritten by the resubmission.
  (let [s (fresh-store)
        first-op (supply-op "op-dup-3")]
    (op/execute-operation first-op s 3)
    (op/execute-operation (supply-op "op-dup-3") s 3)
    (let [records (filter #(= "op-dup-3" (get % :testadmn.proposal/id))
                          (store/proposal-log s))
          effects (map #(get % :testadmn.proposal/effect) records)]
      (is (= :commit (first effects)))
      (is (not (some #(= :commit %) (rest effects)))))))

(deftest test-check29-mem-datomic-parity
  ;; HARD CHECK 29 must behave identically on the Datomic backend: the same
  ;; reuse scenario rejects with the same status/reason on both stores.
  (letfn [(dup-outcome [mk]
            (let [s (mk)]
              (store/register-session! s "sess-001"
                {:testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
                 :testadmn.test-session/facility-id "facility-101"})
              (op/execute-operation (supply-op "op-parity") s 3)
              (op/execute-operation (supply-op "op-parity") s 3)))]
    (let [mem (dup-outcome store/new-mem-store)
          dat (dup-outcome store/new-datomic-store)]
      (is (= :rejected (:status mem)))
      (is (= :rejected (:status dat)))
      (is (= "proposal-id-duplicate" (:reason mem)))
      (is (= "proposal-id-duplicate" (:reason dat))))))

(deftest test-check29-check8-still-last-after-check29
  ;; Structural invariant: the attendance self-contradiction check (check 8)
  ;; remains the LAST entry of the governor's checks vector, so the integrity
  ;; test that reads (last :checks) still sees it.
  (let [s (fresh-store)
        result (gov/evaluate-proposal s (supply-op "op-order-1"))
        last-check (last (:checks result))]
    ;; the supply op trivially passes check 8 -- its reason proves identity
    (is (= "not-an-attendance-note" (:reason last-check)))))
