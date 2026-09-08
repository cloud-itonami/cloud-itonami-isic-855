;; testadmn_venue_double_booking_test — HARD CHECK 25: no venue-time double-booking.
;;
;; ISIC-855 one physical room hosts ONE exam at a time. The target session of a
;; :schedule-test-session must not share its (:facility-id, :scheduled-start)
;; pair with any OTHER registered session in the store. HARD CHECK 12
;; (schedule-verified) only requires the venue and the start to be PRESENT,
;; and HARD CHECK 18 only requires an enrolled roster — none has ever compared
;; the target's (venue, time) against the rest of the session population. A
;; schedule that books facility-101 at 2026-07-15T09:00:00Z while facility-101
;; is already registered for a different exam at exactly that instant passes
;; checks 1-24 and, at Phase 3, would auto-commit two exams into one room — a
;; collision that surfaces only physically at check-in. HARD CHECK 25 rejects
;; it outright — never held, never auto-committed at Phase 3.

(ns testadmn-venue-double-booking-test
  (:require [clojure.test :refer [deftest is testing]]
            [testadmn.store :as store]
            [testadmn.governor :as gov]
            [testadmn.operation :as op]))

(defn- schedule-proposal [session-id]
  {:testadmn.proposal/id (str "vb-" session-id)
   :testadmn.proposal/type :schedule-test-session
   :testadmn.proposal/effect :propose
   :testadmn.proposal/target-session-id session-id
   :testadmn.proposal/proposal-data {:room "Gym A" :proctors 3}})

(defn- occupied-store
  "A store where sess-001 is fully scheduled/registered at facility-101 @
   2026-07-15T09:00:00Z (the room is taken), and sess-002 is a registered,
   venued, start-ed, rostered sibling whose venue/time the caller decides."
  []
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/name "SAT Administration 2026-07-15"
       :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-101"
       :testadmn.test-session/roster #{"s001" "s002"}})
    s))

(deftest test-schedule-to-free-venue-time-passes
  ;; A second session booked into a DIFFERENT room at the same time — or the
  ;; same room at a different time — does not collide.
  (let [s (occupied-store)]
    (store/register-session! s "sess-002"
      {:testadmn.test-session/name "ACT Administration 2026-07-15"
       :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-204"
       :testadmn.test-session/roster #{"s003"}})
    (let [result (gov/evaluate-proposal s (schedule-proposal "sess-002"))]
      (is (true? (:accepted? result)))
      (is (= "all-checks-pass" (:reason result))))))

(deftest test-schedule-onto-occupied-venue-time-rejected
  ;; sess-002 claims facility-101 at the exact start already registered to
  ;; sess-001: two exams, one room, one instant — rejected, with the collision
  ;; partner named.
  (let [s (occupied-store)]
    (store/register-session! s "sess-002"
      {:testadmn.test-session/name "SAT Retake 2026-07-15"
       :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-101"
       :testadmn.test-session/roster #{"s003" "s004"}})
    (let [result (gov/evaluate-proposal s (schedule-proposal "sess-002"))
          check25 (some #(when (= "schedule-venue-time-collision" (:reason %)) %)
                        (:checks result))]
      (is (false? (:accepted? result)))
      (is (= "schedule-venue-time-collision" (:reason result)))
      (is (= "sess-002" (:session-id check25)))
      (is (= ["sess-001"] (:collides-with check25)))
      (is (= ["facility-101" "2026-07-15T09:00:00Z"] (:venue-time check25))))))

(deftest test-unregistered-twin-does-not-occupy-the-room
  ;; A session sharing the venue/time but NOT registered cannot occupy
  ;; anything: only registered sessions block a slot, so a registered target
  ;; schedules cleanly even with an unregistered draft twin parked at the
  ;; same room and instant.
  (let [s (store/new-mem-store)]
    (store/create-session! s "twin-draft"
      {:testadmn.test-session/name "Draft twin"
       :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-101"
       :testadmn.test-session/roster #{"s009"}})
    (store/register-session! s "sess-002"
      {:testadmn.test-session/name "SAT Administration 2026-07-15"
       :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-101"
       :testadmn.test-session/roster #{"s001" "s002"}})
    (let [result (gov/evaluate-proposal s (schedule-proposal "sess-002"))]
      (is (true? (:accepted? result)))
      (is (= "all-checks-pass" (:reason result))))))

(deftest test-venue-less-target-cannot-mask-a-collision
  ;; A blank venue (or start) on the target yields no occupancy key, so check
  ;; 25 defers to check 12 — which rejects the venue-less schedule outright.
  (let [s (occupied-store)]
    (store/register-session! s "sess-002"
      {:testadmn.test-session/name "Venue-less twin"
       :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id ""
       :testadmn.test-session/roster #{"s003"}})
    (let [result (gov/evaluate-proposal s (schedule-proposal "sess-002"))]
      (is (false? (:accepted? result)))
      (is (= "schedule-missing-facility" (:reason result))))))

(deftest test-non-schedule-op-unaffected-by-booking-check
  ;; HARD CHECK 25 applies only to :schedule-test-session. An attendance note
  ;; for an already-occupied room passes trivially.
  (let [s (occupied-store)
        proposal {:testadmn.proposal/id "vb-att"
                  :testadmn.proposal/type :log-attendance-note
                  :testadmn.proposal/effect :propose
                  :testadmn.proposal/target-session-id "sess-001"
                  :testadmn.proposal/proposal-data
                  {:check-in ["s001" "s002"]}}
        result (gov/evaluate-proposal s proposal)
        check25 (some #(when (contains? #{"not-a-schedule"
                                          "schedule-venue-time-collision"
                                          "schedule-venue-time-free"
                                          "schedule-venue-time-unasserted"}
                                        (:reason %))
                         %)
                      (:checks result))]
    (is (true? (:accepted? result)))
    (is (= "not-a-schedule" (:reason check25)))))

(deftest test-double-booked-schedule-never-auto-commits-phase3
  ;; End to end at Phase 3: the colliding schedule is rejected, never
  ;; auto-committed two exams into one room.
  (let [s (occupied-store)]
    (store/register-session! s "sess-002"
      {:testadmn.test-session/name "SAT Retake 2026-07-15"
       :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-101"
       :testadmn.test-session/roster #{"s003" "s004"}})
    (let [operation (op/make-operation :schedule-test-session "sess-002"
                                        {:room "Gym A" :proctors 3})
          result (op/execute-operation operation s 3)]
      (is (= :rejected (:status result)))
      (is (= "schedule-venue-time-collision" (:reason result))))))

(deftest test-check8-still-last-after-check25
  ;; HARD CHECK 8 stays the LAST entry of the governor's :checks vector, so
  ;; testadmn_integrity_test's (last :checks) contract keeps holding after
  ;; HARD CHECK 25 is added.
  (let [s (occupied-store)
        proposal {:testadmn.proposal/id "vb-25b"
                  :testadmn.proposal/type :log-attendance-note
                  :testadmn.proposal/effect :propose
                  :testadmn.proposal/target-session-id "sess-001"
                  :testadmn.proposal/proposal-data
                  {:check-in ["s001"] :absent ["s002"]}}
        result (gov/evaluate-proposal s proposal)
        check8 (last (:checks result))]
    (is (= "attendance-non-contradictory" (:reason check8)))))

(deftest test-booking-check-parity-across-backends
  ;; The collision is detected identically on the Datomic-backed store: both
  ;; backends satisfy list-sessions and the same rejection.
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
          {:testadmn.test-session/name "SAT Retake 2026-07-15"
           :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
           :testadmn.test-session/facility-id "facility-101"
           :testadmn.test-session/roster #{"s003"}})
        (let [result (gov/evaluate-proposal s (schedule-proposal "sess-002"))]
          (is (false? (:accepted? result)))
          (is (= "schedule-venue-time-collision" (:reason result)))
          (is (= 2 (count (store/list-sessions s)))))))))
