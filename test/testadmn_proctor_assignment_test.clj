;; testadmn.proctor-assignment-test — HARD CHECK 10: proctor assignment non-empty.
;;
;; ISIC-855 test administration assigns proctors to a session through the
;; :coordinate-proctor-assignment-proposal op (approval-gated at Phase 2,
;; auto-committed at Phase 3). HARD CHECK 4 (impartiality) only rejects
;; proctors DECLARED conflicted — it says nothing about an assignment naming NO
;; proctors. Today a vacuous assignment passes checks 1-9 and, at Phase 3,
;; auto-commits "no one is assigned" for a session that HARD CHECK 9 (staffing)
;; already required to be staffed (:proctors >= 1). HARD CHECK 10 closes that:
;; an empty assignment is rejected outright, never held, never auto-committed.

(ns testadmn-proctor-assignment-test
  (:require [clojure.test :refer [deftest is]]
            [testadmn.store :as store]
            [testadmn.governor :as gov]
            [testadmn.operation :as op]))

(defn- pa-proposal [proposal-data]
  {:testadmn.proposal/id "pa-1"
   :testadmn.proposal/type :coordinate-proctor-assignment-proposal
   :testadmn.proposal/effect :propose
   :testadmn.proposal/target-session-id "sess-001"
   :testadmn.proposal/proposal-data proposal-data})

(defn- pa10-result
  "The HARD-check result whose reason names the proctor-assignment decision."
  [result]
  (->> (:checks result)
       (filter (fn [c] (contains? #{"proctors-named" "proctor-assignment-empty"}
                                  (:reason c))))
       first))

(deftest test-nonempty-proctor-maps-pass
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [result (gov/evaluate-proposal s
                  (pa-proposal {:proctors [{:proctor/id "A. Yamada"
                                            :testadmn.proposal/proctor-impartial? true}]}))]
      (is (true? (:accepted? result)))
      (is (= "all-checks-pass" (:reason result)))
      (is (= "proctors-named" (:reason (pa10-result result)))))))

(deftest test-nonempty-string-proctors-pass
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [result (gov/evaluate-proposal s (pa-proposal {:proctors ["A. Smith"]}))]
      (is (true? (:accepted? result)))
      (is (= "proctors-named" (:reason (pa10-result result)))))))

(deftest test-empty-proctor-assignment-rejected
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [result (gov/evaluate-proposal s (pa-proposal {:proctors []}))]
      (is (false? (:accepted? result)))
      (is (= "proctor-assignment-empty" (:reason result))))))

(deftest test-proctors-omitted-rejected
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [result (gov/evaluate-proposal s (pa-proposal {}))]
      (is (false? (:accepted? result)))
      (is (= "proctor-assignment-empty" (:reason result))))))

(deftest test-non-proctor-op-unaffected
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/name "SAT Administration 2026-07-15"
       :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-101"})
    (let [result (gov/evaluate-proposal s
                  {:testadmn.proposal/id "p1"
                   :testadmn.proposal/type :schedule-test-session
                   :testadmn.proposal/effect :propose
                   :testadmn.proposal/target-session-id "sess-001"
                   :testadmn.proposal/proposal-data {:room "Gym A" :proctors 3}})]
      (is (true? (:accepted? result)))
      (is (every? #(true? (:pass? %)) (:checks result))))))

(deftest test-empty-proctor-assignment-never-auto-commits-phase3
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [operation (op/make-operation :coordinate-proctor-assignment-proposal
                                       "sess-001" {:proctors []})
          result (op/execute-operation operation s 3)]
      (is (= :rejected (:status result)))
      (is (= "proctor-assignment-empty" (:reason result))))))

(deftest test-nonempty-proctor-assignment-still-auto-commits-phase3
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001" {})
    (let [operation (op/make-operation :coordinate-proctor-assignment-proposal
                                       "sess-001" {:proctors ["A. Smith"]})
          result (op/execute-operation operation s 3)]
      (is (= :auto-committed (:status result))))))