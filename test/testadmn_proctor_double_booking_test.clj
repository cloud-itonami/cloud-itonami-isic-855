;; testadmn_proctor_double_booking_test — HARD CHECK 28: no proctor
;; double-booking across simultaneous sessions.
;;
;; ISIC-855 one staff member supervises ONE exam at a time. HARD CHECK 25
;; keeps one ROOM out of two simultaneous exams and HARD CHECK 26 keeps one
;; TEST-TAKER out of two, but nothing kept one PROCTOR out of two: two
;; registered sessions in different facilities sharing an instant never trip
;; the venue-time pair, and disjoint rosters never trip the person check, so
;; a schedule declaring supervision the same staff member cannot physically
;; serve passed checks 1-27 and auto-committed at Phase 3. HARD CHECK 28
;; rejects it outright — never held, never auto-committed at Phase 3.

(ns testadmn-proctor-double-booking-test
  (:require [clojure.test :refer [deftest is testing]]
            [testadmn.store :as store]
            [testadmn.governor :as gov]
            [testadmn.operation :as op]))

(defn- schedule-proposal
  [session-id & {:keys [supervision] :or {supervision ["P. Okafor" "L. Marsh"]}}]
  {:testadmn.proposal/id (str "pdb-" session-id)
   :testadmn.proposal/type :schedule-test-session
   :testadmn.proposal/effect :propose
   :testadmn.proposal/target-session-id session-id
   :testadmn.proposal/proposal-data
   {:room "Gym A" :proctors 3 :proctor-supervision supervision}})

(defn- base-store
  "A store where sess-001 is fully scheduled/registered at facility-101 @
   2026-07-15T09:00:00Z with supervision #{\"P. Okafor\" \"L. Marsh\"} and a
   roster disjoint from every other session here. The caller decides the
   venue/time/supervision of registered sibling sess-002."
  []
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/name "SAT Administration 2026-07-15"
       :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-101"
       :testadmn.test-session/roster #{"s001" "s002"}
       :testadmn.test-session/supervision #{"P. Okafor" "L. Marsh"}})
    s))

(defn- check28 [result]
  (some #(when (contains? #{"not-a-schedule"
                            "schedule-start-time-unasserted"
                            "schedule-supervision-unasserted"
                            "schedule-proctor-time-collision"
                            "schedule-proctor-time-free"}
                          (:reason %))
           %)
        (:checks result)))

(deftest test-disjoint-supervision-at-same-instant-passes
  ;; Two sessions at the SAME instant in DIFFERENT rooms with different
  ;; supervising staff: neither the room (check 25), the bodies (check 26),
  ;; nor the proctors (check 28) collide, so the schedule commits cleanly.
  (let [s (base-store)]
    (store/register-session! s "sess-002"
      {:testadmn.test-session/name "ACT Administration 2026-07-15"
       :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-204"
       :testadmn.test-session/roster #{"s003" "s004"}
       :testadmn.test-session/supervision #{"D. Reyes"}})
    (let [result (gov/evaluate-proposal s (schedule-proposal "sess-002" :supervision ["D. Reyes"]))]
      (is (true? (:accepted? result)))
      (is (= "all-checks-pass" (:reason result)))
      (is (= "schedule-proctor-time-free" (:reason (check28 result)))))))

(deftest test-shared-proctor-at-same-instant-rejected
  ;; sess-002 runs in a DIFFERENT room at the SAME start and declares P.
  ;; Okafor, already supervising sess-001 at that instant: two exams, one
  ;; supervisor, one time — rejected, with the collision partner and the
  ;; shared staff member named.
  (let [s (base-store)]
    (store/register-session! s "sess-002"
      {:testadmn.test-session/name "SAT Retake Annex 2026-07-15"
       :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-204"
       :testadmn.test-session/roster #{"s003" "s004"}
       :testadmn.test-session/supervision #{"P. Okafor" "D. Reyes"}})
    (let [result (gov/evaluate-proposal s (schedule-proposal "sess-002" :supervision ["P. Okafor"]))
          c (check28 result)]
      (is (false? (:accepted? result)))
      (is (= "schedule-proctor-time-collision" (:reason result)))
      (is (= "sess-002" (:session-id c)))
      (is (= "2026-07-15T09:00:00Z" (:scheduled-start c)))
      (is (= [{:session "sess-001" :proctors ["P. Okafor"]}] (:collisions c))))))

(deftest test-same-proctor-at-different-times-is-fine
  ;; A staff member legitimately supervises a morning and an afternoon exam:
  ;; the collision is only against sessions at the SAME instant.
  (let [s (base-store)]
    (store/register-session! s "sess-002"
      {:testadmn.test-session/name "SAT Afternoon 2026-07-15"
       :testadmn.test-session/scheduled-start "2026-07-15T13:00:00Z"
       :testadmn.test-session/facility-id "facility-204"
       :testadmn.test-session/roster #{"s003"}
       :testadmn.test-session/supervision #{"P. Okafor"}})
    (let [result (gov/evaluate-proposal s (schedule-proposal "sess-002"))]
      (is (true? (:accepted? result)))
      (is (= "all-checks-pass" (:reason result))))))

(deftest test-unregistered-twin-shares-no-proctors
  ;; A session sharing the instant and a supervising staff member but NOT
  ;; registered cannot occupy a supervisor: only registered sessions block a
  ;; staff member's time.
  (let [s (store/new-mem-store)]
    (store/create-session! s "twin-draft"
      {:testadmn.test-session/name "Draft twin"
       :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-204"
       :testadmn.test-session/roster #{"s003"}
       :testadmn.test-session/supervision #{"P. Okafor"}})
    (store/register-session! s "sess-002"
      {:testadmn.test-session/name "SAT Administration 2026-07-15"
       :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-101"
       :testadmn.test-session/roster #{"s001" "s002"}
       :testadmn.test-session/supervision #{"P. Okafor"}})
    (let [result (gov/evaluate-proposal s (schedule-proposal "sess-002"))]
      (is (true? (:accepted? result)))
      (is (= "all-checks-pass" (:reason result))))))

(deftest test-unasserted-supervision-passes-vacuously
  ;; A schedule that names NO supervising staff asserts no body check 28
  ;; could double-book: the headcount sufficiency is check 27's decision,
  ;; not this one.
  (let [s (base-store)]
    (store/register-session! s "sess-002"
      {:testadmn.test-session/name "SAT Annex 2026-07-15"
       :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-204"
       :testadmn.test-session/roster #{"s003" "s004"}})
    (let [result (gov/evaluate-proposal s (schedule-proposal "sess-002" :supervision []))]
      (is (true? (:accepted? result)))
      (is (= "schedule-supervision-unasserted" (:reason (check28 result)))))))

(deftest test-time-less-target-cannot-mask-a-proctor-collision
  ;; A blank start on the target yields no instant key, so check 28 defers
  ;; to check 12 — which rejects the start-less schedule outright.
  (let [s (base-store)]
    (store/register-session! s "sess-002"
      {:testadmn.test-session/name "Time-less twin"
       :testadmn.test-session/scheduled-start ""
       :testadmn.test-session/facility-id "facility-204"
       :testadmn.test-session/roster #{"s003"}
       :testadmn.test-session/supervision #{"P. Okafor"}})
    (let [result (gov/evaluate-proposal s (schedule-proposal "sess-002"))]
      (is (false? (:accepted? result)))
      (is (= "schedule-missing-start" (:reason result))))))

(deftest test-non-schedule-op-unaffected
  ;; The check only guards :schedule-test-session; other ops pass check 28
  ;; trivially.
  (let [s (base-store)
        proposal {:testadmn.proposal/id "pdb-att"
                  :testadmn.proposal/type :log-attendance-note
                  :testadmn.proposal/effect :propose
                  :testadmn.proposal/target-session-id "sess-001"
                  :testadmn.proposal/proposal-data
                  {:check-in ["s001"] :absent ["s002"]}}
        result (gov/evaluate-proposal s proposal)]
    (is (= "not-a-schedule" (:reason (check28 result))))))

(deftest test-proctor-collision-never-auto-commits-phase3
  ;; End to end at Phase 3: the colliding supervision plan is rejected,
  ;; never auto-committed.
  (let [s (base-store)]
    (store/register-session! s "sess-002"
      {:testadmn.test-session/name "SAT Retake Annex 2026-07-15"
       :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-204"
       :testadmn.test-session/roster #{"s003" "s004"}
       :testadmn.test-session/supervision #{"L. Marsh"}})
    (let [operation (op/make-operation :schedule-test-session "sess-002"
                                       {:room "Gym B" :proctors 2
                                        :proctor-supervision ["L. Marsh"]})
          result (op/execute-operation operation s 3)]
      (is (= :rejected (:status result)))
      (is (= "schedule-proctor-time-collision" (:reason result))))))

(deftest test-check8-still-last-after-check28
  ;; HARD CHECK 8 stays the LAST entry of the governor's :checks vector, so
  ;; testadmn_integrity_test's (last :checks) contract keeps holding after
  ;; HARD CHECK 28 is added.
  (let [s (base-store)
        proposal {:testadmn.proposal/id "pdb-28b"
                  :testadmn.proposal/type :log-attendance-note
                  :testadmn.proposal/effect :propose
                  :testadmn.proposal/target-session-id "sess-001"
                  :testadmn.proposal/proposal-data
                  {:check-in ["s001"] :absent ["s002"]}}
        result (gov/evaluate-proposal s proposal)
        check8 (last (:checks result))]
    (is (= "attendance-non-contradictory" (:reason check8)))))

(deftest test-proctor-collision-parity-across-backends
  ;; The supervision-overlap check reads the same on the Datomic-backed
  ;; store: the supervision set round-trips and still trips check 28.
  (doseq [[make-store label] [[store/new-mem-store "MemStore"]
                              [store/new-datomic-store "DatomicStore"]]]
    (testing label
      (let [s (make-store)]
        (store/register-session! s "sess-001"
          {:testadmn.test-session/name "SAT Administration 2026-07-15"
           :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
           :testadmn.test-session/facility-id "facility-101"
           :testadmn.test-session/roster #{"s001" "s002"}
           :testadmn.test-session/supervision #{"P. Okafor" "L. Marsh"}})
        (store/register-session! s "sess-002"
          {:testadmn.test-session/name "SAT Retake Annex 2026-07-15"
           :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
           :testadmn.test-session/facility-id "facility-204"
           :testadmn.test-session/roster #{"s003" "s004"}
           :testadmn.test-session/supervision #{"P. Okafor"}})
        (let [result (gov/evaluate-proposal s (schedule-proposal "sess-002" :supervision ["P. Okafor"]))]
          (is (false? (:accepted? result)))
          (is (= "schedule-proctor-time-collision" (:reason result)))
          (is (= [{:session "sess-001" :proctors ["P. Okafor"]}]
                 (:collisions (check28 result)))))))))
