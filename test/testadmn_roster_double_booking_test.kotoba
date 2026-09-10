;; testadmn_roster_double_booking_test — HARD CHECK 26: no roster-member time collision.
;;
;; ISIC-855 one registered test-taker sits ONE exam at a time. HARD CHECK 25
;; keeps one room out of two simultaneous exams; nothing kept one PERSON out
;; of two. The target session of a :schedule-test-session must not share an
;; enrolled test-taker with any OTHER registered session starting at the same
;; instant. A student enrolled to the 09:00 SAT in facility-101 and the 09:00
;; ACT in facility-204 passes checks 1-25 — the venue-time pairs differ, so
;; the room check never fires — and would auto-commit at Phase 3 an impossible
;; double-seating that surfaces only at check-in. HARD CHECK 26 rejects it
;; outright — never held, never auto-committed at Phase 3.

(ns testadmn-roster-double-booking-test
  (:require [clojure.test :refer [deftest is testing]]
            [testadmn.store :as store]
            [testadmn.governor :as gov]
            [testadmn.operation :as op]))

(defn- schedule-proposal [session-id]
  {:testadmn.proposal/id (str "rb-" session-id)
   :testadmn.proposal/type :schedule-test-session
   :testadmn.proposal/effect :propose
   :testadmn.proposal/target-session-id session-id
   :testadmn.proposal/proposal-data {:room "Hall B" :proctors 3}})

(defn- occupied-store
  "A store where sess-001 is fully scheduled/registered at facility-101 @
   2026-07-15T09:00:00Z with roster #{\"s001\" \"s002\"}. The caller decides
   the venue/time/roster of registered sibling sess-002."
  []
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/name "SAT Administration 2026-07-15"
       :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-101"
       :testadmn.test-session/roster #{"s001" "s002"}})
    s))

(deftest test-schedule-with-disjoint-rosters-passes
  ;; Two sessions at the SAME instant in DIFFERENT rooms with no shared
  ;; test-taker: neither the room (check 25) nor the bodies collide, so the
  ;; schedule commits cleanly.
  (let [s (occupied-store)]
    (store/register-session! s "sess-002"
      {:testadmn.test-session/name "ACT Administration 2026-07-15"
       :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-204"
       :testadmn.test-session/roster #{"s003" "s004"}})
    (let [result (gov/evaluate-proposal s (schedule-proposal "sess-002"))]
      (is (true? (:accepted? result)))
      (is (= "all-checks-pass" (:reason result))))))

(deftest test-shared-test-taker-at-same-instant-rejected
  ;; sess-002 runs in a DIFFERENT room at the SAME start but re-enrolls s002,
  ;; who already sits sess-001 at that instant: two exams, one body, one time —
  ;; rejected, with the collision partner and the shared person named.
  (let [s (occupied-store)]
    (store/register-session! s "sess-002"
      {:testadmn.test-session/name "SAT Retake Annex 2026-07-15"
       :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-204"
       :testadmn.test-session/roster #{"s002" "s003"}})
    (let [result (gov/evaluate-proposal s (schedule-proposal "sess-002"))
          check26 (some #(when (= "schedule-roster-time-collision" (:reason %)) %)
                        (:checks result))]
      (is (false? (:accepted? result)))
      (is (= "schedule-roster-time-collision" (:reason result)))
      (is (= "sess-002" (:session-id check26)))
      (is (= "2026-07-15T09:00:00Z" (:scheduled-start check26)))
      (is (= [{:session "sess-001" :test-takers ["s002"]}] (:collisions check26))))))

(deftest test-same-test-taker-at-different-times-is-fine
  ;; A student legitimately sits a morning and an afternoon exam: the
  ;; collision is only against sessions at the SAME instant.
  (let [s (occupied-store)]
    (store/register-session! s "sess-002"
      {:testadmn.test-session/name "SAT Afternoon 2026-07-15"
       :testadmn.test-session/scheduled-start "2026-07-15T13:00:00Z"
       :testadmn.test-session/facility-id "facility-204"
       :testadmn.test-session/roster #{"s002"}})
    (let [result (gov/evaluate-proposal s (schedule-proposal "sess-002"))]
      (is (true? (:accepted? result)))
      (is (= "all-checks-pass" (:reason result))))))

(deftest test-unregistered-twin-shares-no-bodies
  ;; A session sharing the instant and roster member but NOT registered cannot
  ;; occupy a person: only registered sessions block a test-taker's time.
  (let [s (store/new-mem-store)]
    (store/create-session! s "twin-draft"
      {:testadmn.test-session/name "Draft twin"
       :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-204"
       :testadmn.test-session/roster #{"s002"}})
    (store/register-session! s "sess-002"
      {:testadmn.test-session/name "SAT Administration 2026-07-15"
       :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-101"
       :testadmn.test-session/roster #{"s001" "s002"}})
    (let [result (gov/evaluate-proposal s (schedule-proposal "sess-002"))]
      (is (true? (:accepted? result)))
      (is (= "all-checks-pass" (:reason result))))))

(deftest test-time-less-target-cannot-mask-a-collision
  ;; A blank start on the target yields no instant key, so check 26 defers to
  ;; check 12 — which rejects the start-less schedule outright.
  (let [s (occupied-store)]
    (store/register-session! s "sess-002"
      {:testadmn.test-session/name "Time-less twin"
       :testadmn.test-session/scheduled-start ""
       :testadmn.test-session/facility-id "facility-204"
       :testadmn.test-session/roster #{"s002"}})
    (let [result (gov/evaluate-proposal s (schedule-proposal "sess-002"))]
      (is (false? (:accepted? result)))
      (is (= "schedule-missing-start" (:reason result))))))

(deftest test-non-schedule-op-unaffected-by-roster-check
  ;; HARD CHECK 26 applies only to :schedule-test-session. An attendance note
  ;; passes trivially.
  (let [s (occupied-store)
        proposal {:testadmn.proposal/id "rb-att"
                  :testadmn.proposal/type :log-attendance-note
                  :testadmn.proposal/effect :propose
                  :testadmn.proposal/target-session-id "sess-001"
                  :testadmn.proposal/proposal-data
                  {:check-in ["s001" "s002"]}}
        result (gov/evaluate-proposal s proposal)
        check26 (some #(when (contains? #{"not-a-schedule"
                                          "schedule-roster-time-collision"
                                          "schedule-roster-time-free"
                                          "schedule-start-time-unasserted"}
                                        (:reason %))
                         %)
                      (:checks result))]
    (is (true? (:accepted? result)))
    (is (= "not-a-schedule" (:reason check26)))))

(deftest test-double-seated-schedule-never-auto-commits-phase3
  ;; End to end at Phase 3: the colliding schedule is rejected, never
  ;; auto-committed two exams onto one body.
  (let [s (occupied-store)]
    (store/register-session! s "sess-002"
      {:testadmn.test-session/name "SAT Retake Annex 2026-07-15"
       :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-204"
       :testadmn.test-session/roster #{"s002" "s003"}})
    (let [operation (op/make-operation :schedule-test-session "sess-002"
                                        {:room "Hall B" :proctors 3})
          result (op/execute-operation operation s 3)]
      (is (= :rejected (:status result)))
      (is (= "schedule-roster-time-collision" (:reason result))))))

(deftest test-check8-still-last-after-check26
  ;; HARD CHECK 8 stays the LAST entry of the governor's :checks vector, so
  ;; testadmn_integrity_test's (last :checks) contract keeps holding after
  ;; HARD CHECK 26 is added.
  (let [s (occupied-store)
        proposal {:testadmn.proposal/id "rb-26b"
                  :testadmn.proposal/type :log-attendance-note
                  :testadmn.proposal/effect :propose
                  :testadmn.proposal/target-session-id "sess-001"
                  :testadmn.proposal/proposal-data
                  {:check-in ["s001"] :absent ["s002"]}}
        result (gov/evaluate-proposal s proposal)
        check8 (last (:checks result))]
    (is (= "attendance-non-contradictory" (:reason check8)))))

(deftest test-roster-collision-parity-across-backends
  ;; The double-seating is detected identically on the Datomic-backed store:
  ;; both backends satisfy list-sessions and the same rejection.
  (doseq [[make-store label] [[store/new-mem-store "MemStore"]
                              [store/new-datomic-store "DatomicStore"]]]
    (testing label
      (let [s (make-store)]
        (store/register-session! s "sess-001"
          {:testadmn.test-session/name "SAT Administration 2026-07-15"
           :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
           :testadmn.test-session/facility-id "facility-101"
           :testadmn.test-session/roster #{"s001" "s002"}})
        (store/register-session! s "sess-002"
          {:testadmn.test-session/name "SAT Retake Annex 2026-07-15"
           :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
           :testadmn.test-session/facility-id "facility-204"
           :testadmn.test-session/roster #{"s002" "s003"}})
        (let [result (gov/evaluate-proposal s (schedule-proposal "sess-002"))]
          (is (false? (:accepted? result)))
          (is (= "schedule-roster-time-collision" (:reason result)))
          (is (= 2 (count (store/list-sessions s)))))))))
