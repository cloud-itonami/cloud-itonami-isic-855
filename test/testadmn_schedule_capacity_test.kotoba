;; testadmn_schedule_capacity_test — HARD CHECK 27: schedule supervision
;; must cover enrollment.
;;
;; ISIC-855 every seated test-taker must be within a proctor's watch. HARD
;; CHECK 9 requires a :schedule-test-session to declare a POSITIVE :proctors
;; headcount, and HARD CHECK 18 requires a non-empty enrolled roster — but
;; nothing compared the two figures: a schedule declaring 1 proctor for a
;; 26-person roster passes checks 1–26 and would auto-commit at Phase 3 an
;; unsupervisable exam — unchecked stations are where misconduct goes unseen,
;; so under-staffing is an exam-integrity hole, not a comfort one. The
;; check 25/26 family closed occupancy collisions for the ROOM and the
;; PERSON; this closes the capacity collision for the SUPERVISOR: one proctor
;; watches at most max-seats-per-proctor (25) seated test-takers. HARD CHECK
;; 27 rejects roster-size > proctors * cap outright — never held, never
;; auto-committed at Phase 3.

(ns testadmn-schedule-capacity-test
  (:require [clojure.test :refer [deftest is testing]]
            [testadmn.store :as store]
            [testadmn.governor :as gov]
            [testadmn.operation :as op]))

(defn- schedule-proposal [session-id & {:keys [proctors] :or {proctors 1}}]
  {:testadmn.proposal/id (str "cap-" session-id "-" proctors)
   :testadmn.proposal/type :schedule-test-session
   :testadmn.proposal/effect :propose
   :testadmn.proposal/target-session-id session-id
   :testadmn.proposal/proposal-data {:room "Gym A" :proctors proctors}})

;; A deterministic enrolled roster of n distinct string ids.
(defn- roster-of [n]
  (set (map #(str "s" %) (range n))))

;; A store with one registered, venued, start-timed session whose enrolled
;; roster carries exactly `roster-size` test-takers.
(defn- session-store [roster-size]
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/name "SAT Administration 2026-07-15"
       :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-101"
       :testadmn.test-session/roster (roster-of roster-size)})
    s))

(defn- check27 [result]
  (some #(when (contains? #{"not-a-schedule"
                            "schedule-headcount-unasserted"
                            "schedule-roster-unasserted"
                            "schedule-understaffed-for-roster"
                            "schedule-capacity-covered"}
                          (:reason %))
           %)
        (:checks result)))

(deftest test-roster-within-proctor-capacity-passes
  ;; 25 enrolled seats under ONE proctor is exactly at the cap, and 20 under
  ;; one proctor is inside it: both schedule cleanly.
  (doseq [[size label] [[25 "at-cap"] [20 "under-cap"] [3 "demo-size"]]]
    (testing label
      (let [result (gov/evaluate-proposal (session-store size)
                                          (schedule-proposal "sess-001" :proctors 1))]
        (is (true? (:accepted? result)))
        (is (= "all-checks-pass" (:reason result)))
        (is (= "schedule-capacity-covered" (:reason (check27 result))))))))

(deftest test-understaffed-schedule-rejected
  ;; 26 enrolled seats under ONE proctor is one seat outside every proctor's
  ;; watch: rejected, with the capacity figures named.
  (let [result (gov/evaluate-proposal (session-store 26)
                                      (schedule-proposal "sess-001" :proctors 1))
        c (check27 result)]
    (is (false? (:accepted? result)))
    (is (= "schedule-understaffed-for-roster" (:reason result)))
    (is (= "sess-001" (:session-id c)))
    (is (= 1 (:proctors c)))
    (is (= 26 (:roster-size c)))
    (is (= 25 (:capacity c)))))

(deftest test-one-extra-proctor-clears-the-collision
  ;; The 26-seat roster schedules cleanly with TWO proctors (cap 50): the
  ;; check is a staffing ratio, not a hard session-size limit.
  (let [result (gov/evaluate-proposal (session-store 26)
                                      (schedule-proposal "sess-001" :proctors 2))]
    (is (true? (:accepted? result)))
    (is (= "all-checks-pass" (:reason result)))))

(deftest test-unasserted-headcount-or-roster-defers-to-earlier-checks
  ;; A zero headcount is check 9's decision, and an empty roster is check
  ;; 18's — check 27 never fires second on either; it only compares two
  ;; asserted figures.
  (let [zero-head (gov/evaluate-proposal (session-store 30)
                                         (schedule-proposal "sess-001" :proctors 0))
        empty-roster-store (doto (store/new-mem-store)
                             (store/register-session! "sess-001"
                               {:testadmn.test-session/name "Empty"
                                :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
                                :testadmn.test-session/facility-id "facility-101"}))]
    (is (false? (:accepted? zero-head)))
    (is (= "schedule-no-proctor-staffing" (:reason zero-head)))
    (is (= "schedule-headcount-unasserted" (:reason (check27 zero-head))))
    (let [er (gov/evaluate-proposal empty-roster-store (schedule-proposal "sess-001" :proctors 1))]
      (is (false? (:accepted? er)))
      (is (= "schedule-no-enrolled-roster" (:reason er)))
      (is (= "schedule-roster-unasserted" (:reason (check27 er)))))))

(deftest test-non-schedule-op-unaffected-by-capacity-check
  ;; HARD CHECK 27 applies only to :schedule-test-session.
  (let [s (session-store 26)
        proposal {:testadmn.proposal/id "cap-att"
                  :testadmn.proposal/type :log-attendance-note
                  :testadmn.proposal/effect :propose
                  :testadmn.proposal/target-session-id "sess-001"
                  :testadmn.proposal/proposal-data
                  {:check-in ["s0" "s1"] :absent ["s2"]}}
        result (gov/evaluate-proposal s proposal)]
    (is (= "not-a-schedule" (:reason (check27 result))))))

(deftest test-understaffed-schedule-never-auto-commits-phase3
  ;; End to end at Phase 3: the 26-seat/1-proctor schedule is rejected, never
  ;; auto-committed an unsupervisable exam.
  (let [s (session-store 26)
        operation (op/make-operation :schedule-test-session "sess-001"
                                     {:room "Gym A" :proctors 1})
        result (op/execute-operation operation s 3)]
    (is (= :rejected (:status result)))
    (is (= "schedule-understaffed-for-roster" (:reason result)))))

(deftest test-check8-still-last-after-check27
  ;; HARD CHECK 8 stays the LAST entry of the governor's :checks vector, so
  ;; testadmn_integrity_test's (last :checks) contract keeps holding after
  ;; HARD CHECK 27 is added.
  (let [s (session-store 3)
        proposal {:testadmn.proposal/id "cap-27b"
                  :testadmn.proposal/type :log-attendance-note
                  :testadmn.proposal/effect :propose
                  :testadmn.proposal/target-session-id "sess-001"
                  :testadmn.proposal/proposal-data
                  {:check-in ["s0" "s1"] :absent ["s2"]}}
        result (gov/evaluate-proposal s proposal)
        check8 (last (:checks result))]
    (is (= "attendance-non-contradictory" (:reason check8)))))

(deftest test-capacity-parity-across-backends
  ;; The capacity bound reads the same on the Datomic-backed store: the
  ;; roster round-trips (possibly nested) and still trips check 27.
  (doseq [[make-store label] [[store/new-mem-store "MemStore"]
                              [store/new-datomic-store "DatomicStore"]]]
    (testing label
      (let [s (make-store)]
        (store/register-session! s "sess-001"
          {:testadmn.test-session/name "SAT Administration 2026-07-15"
           :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
           :testadmn.test-session/facility-id "facility-101"
           :testadmn.test-session/roster (roster-of 26)})
        (let [result (gov/evaluate-proposal s (schedule-proposal "sess-001" :proctors 1))]
          (is (false? (:accepted? result)))
          (is (= "schedule-understaffed-for-roster" (:reason result)))
          (is (= 26 (:roster-size (check27 result)))))))))
