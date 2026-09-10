;; testadmn_proctor_enrolled_test — HARD CHECK 21: proctor must not also be
;; an enrolled test-taker of the target session.
;;
;; ISIC-855 test administration assigns proctors to supervise a session. A
;; proctor must not ALSO be one of the test-takers enrolled to sit THAT
;; session. HARD CHECK 4 (impartiality) only inspects the DECLARED
;; :proctor-impartial? boolean, and HARD CHECK 6 (enrollment binding) only
;; binds test-taker names on attendance/accommodation ops — never proctor
;; ids. So a proctor-assignment naming a member of the session's enrolled
;; roster passes checks 1-20 and auto-commits at Phase 3. HARD CHECK 21
;; closes it: a named proctor must not be an enrolled test-taker; rejected
;; outright, never held, never auto-committed at Phase 3.

(ns testadmn-proctor-enrolled-test
  (:require [clojure.test :refer [deftest is]]
            [testadmn.store :as store]
            [testadmn.governor :as gov]
            [testadmn.operation :as op]))

(defn- pa-proposal [proposal-data]
  {:testadmn.proposal/id "pa-21"
   :testadmn.proposal/type :coordinate-proctor-assignment-proposal
   :testadmn.proposal/effect :propose
   :testadmn.proposal/target-session-id "sess-001"
   :testadmn.proposal/proposal-data proposal-data})

(defn- check21-result [result]
  (->> (:checks result)
       (filter (fn [c] (contains? #{"proctors-not-enrolled"
                                    "proctor-is-enrolled-test-taker"
                                    "not-a-proctor-assignment"}
                                  (:reason c))))
       first))

(deftest test-proctors-not-enrolled-pass
  ;; A proctor-assignment naming proctors who are NOT on the session's
  ;; enrolled roster is accepted.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-101"
       :testadmn.test-session/roster #{"s001" "s002"}})
    (let [result (gov/evaluate-proposal s
                  (pa-proposal {:proctors [{:proctor/id "A. Smith"
                                            :testadmn.proposal/proctor-impartial? true}]}))]
      (is (true? (:accepted? result)))
      (is (= "all-checks-pass" (:reason result)))
      (is (= "proctors-not-enrolled" (:reason (check21-result result)))))))

(deftest test-proctor-who-is-enrolled-rejected
  ;; Naming a proctor who IS on the session's enrolled roster (the same id is
  ;; both seated examinee and supervisor) is rejected outright.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-101"
       :testadmn.test-session/roster #{"A. Smith" "s002"}})
    (let [result (gov/evaluate-proposal s
                  (pa-proposal {:proctors [{:proctor/id "A. Smith"
                                            :testadmn.proposal/proctor-impartial? true}]}))]
      (is (false? (:accepted? result)))
      (is (= "proctor-is-enrolled-test-taker" (:reason result))))))

(deftest test-declared-impartial-still-rejected-when-enrolled
  ;; HARD CHECK 4 only reads the DECLARED impartiality flag; even a proctor
  ;; who declares impartiality is still rejected by CHECK 21 if they are an
  ;; enrolled test-taker of the session (a declared flag cannot out-rank
  ;; being the examinee in the same room).
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-101"
       :testadmn.test-session/roster #{"R. Ito"}})
    (let [result (gov/evaluate-proposal s
                  (pa-proposal {:proctors [{:proctor/id "R. Ito"
                                            :testadmn.proposal/proctor-impartial? true}]}))]
      (is (false? (:accepted? result)))
      (is (= "proctor-is-enrolled-test-taker" (:reason result))))))

(deftest test-empty-roster-passes-vacuously
  ;; A session with no enrolled roster has no member a proctor could collide
  ;; with — check 21 passes vacuously.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-101"})
    (let [result (gov/evaluate-proposal s
                  (pa-proposal {:proctors [{:proctor/id "A. Smith"
                                            :testadmn.proposal/proctor-impartial? true}]}))]
      (is (true? (:accepted? result)))
      (is (= "proctors-not-enrolled" (:reason (check21-result result)))))))

(deftest test-non-proctor-op-unaffected
  ;; The check only guards :coordinate-proctor-assignment-proposal; other ops
  ;; pass check 21 trivially.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-101"
       :testadmn.test-session/roster #{"s001" "s002"}})
    (let [result (gov/evaluate-proposal s
                  {:testadmn.proposal/id "p1"
                   :testadmn.proposal/type :schedule-test-session
                   :testadmn.proposal/effect :propose
                   :testadmn.proposal/target-session-id "sess-001"
                   :testadmn.proposal/proposal-data {:room "Gym A" :proctors 3}})]
      (is (true? (:accepted? result)))
      (is (= "not-a-proctor-assignment" (:reason (check21-result result)))))))

(deftest test-enrolled-proctor-never-auto-commits-phase3
  ;; End to end: at Phase 3 a proctor who is an enrolled test-taker is
  ;; rejected by the governor and never auto-committed.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-101"
       :testadmn.test-session/roster #{"A. Smith"}})
    (let [operation (op/make-operation :coordinate-proctor-assignment-proposal "sess-001"
                                        {:proctors [{:proctor/id "A. Smith"
                                                     :testadmn.proposal/proctor-impartial? true}]})
          result (op/execute-operation operation s 3)]
      (is (= :rejected (:status result)))
      (is (= "proctor-is-enrolled-test-taker" (:reason result))))))

(deftest test-non-enrolled-proctor-still-auto-commits-phase3
  ;; A clean proctor (not an enrolled test-taker) still auto-commits at
  ;; Phase 3.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-101"
       :testadmn.test-session/roster #{"s001" "s002"}})
    (let [operation (op/make-operation :coordinate-proctor-assignment-proposal "sess-001"
                                        {:proctors ["A. Smith"]})
          result (op/execute-operation operation s 3)]
      (is (= :auto-committed (:status result))))))

(deftest test-check8-still-last-after-check21
  ;; HARD CHECK 8 (attendance self-contradiction) stays the LAST entry of the
  ;; governor's :checks vector, so testadmn_integrity_test's (last :checks)
  ;; contract keeps holding after HARD CHECK 21 is added.
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
