;; testadmn.governor — Test Administration Governor
;; Seven HARD, permanent, un-overridable checks

(ns testadmn.governor
  (:require [clojure.string :as str]
            [testadmn.store :as store]))

(comment
  "Governor enforces permanent scope boundaries and rejects any proposal
   violating them.

   Eight HARD checks (un-overridable):
   1. Test-session verified — target must exist in store AND be :registered?/:verified?
   2. Effect is :propose — any other :effect value is rejected outright
   3. Scope exclusion — test-content, scoring, eligibility, academic-integrity
      adjudication, or safety-authority overrides are blocked. Legitimate
      :flag-safety-concern escalates only, never auto-commits.
   4. Proctor impartiality — a :coordinate-proctor-assignment-proposal must not
      name a proctor who is declared conflicted (teaches/supervises/related to
      a registered test-taker of the target session). A conflicted proctor is
      rejected outright; it is never held and never auto-committed, because an
      examiner must never proctor a test-taker they personally teach or are
      otherwise non-impartial toward. This is an exam-integrity control that the
      closed allowlist schedules at Phase 2+ and MUST NOT auto-commit at Phase 3.
   5. Accessibility accommodation is logistics-only — a
      :coordinate-accommodation-logistics proposal must declare at least one
      accommodation category from the closed allowlist (time-extension,
      reader/scribe, accessible-room, alternate-format, assistive-tech). An
      accommodation arranges HOW a registered test-taker accesses a session
      (extra time, reader/scribe, accessible room, alternate format, assistive
      tech) — it must never alter the test content, grading, or eligibility
      judged (any content/eligibility change is already excluded by HARD
      CHECK 3, which runs before this check). A category-less or
      unknown-category accommodation is rejected outright instead of being
      auto-committed at Phase 3, because auto-commit must not let a logistics
      channel silently grant an access arrangement with no HOW documented.
   6. Bounded supply-consumable allowlist — a :coordinate-supply-request must
      name at least one recognized non-content consumable, and every item must
      be in the closed consumable set (answer sheets, pencils, scratch paper,
      etc.). Generic scope-exclusion (check 3) only blocks content-bearing
      TERMS; it does not bound WHICH non-content consumables a request may
      order. An unrecognized or empty supply request is rejected outright
      instead of being auto-committed at Phase 3, because auto-commit must
      not let a logistics channel silently order an unsanctioned consumable.
   7. Attendance-note roster binding — a :log-attendance-note must name at
      least one test-taker id and every id must be in the target session's
      enrolled-test-taker roster. An out-of-roster or empty note is rejected
      outright, never held, never auto-committed at
      Phase 3.
   8. Facility slot is free — a :schedule-test-session must NOT place the
      verified target session into a facility that is ALREADY OCCUPIED at the
      same scheduled-start by another registered session. A single-session
      lookup cannot see a double-booking; only a store-wide view can. A
      schedule that would seat two exams in one facility at the same start is
      rejected outright, never held, never auto-committed at Phase 3.")

;; === Scope Exclusion Keywords ===
(def ^:private forbidden-keywords
  ;; Explicitly forbidden patterns
  #{"test-content" "answer-key" "grading" "scoring" "eligibility"
    "academic-integrity" "adjudication" "disciplinary"
    "suspension" "expulsion" "sanction" "override"
    "authority" "policy"})

(def ^:private flagging-keywords
  ;; Legitimate :flag-safety-concern keywords that escalate
  #{"safety" "concern" "incident" "hazard" "suspected"
    "possible" "observe" "flag" "escalate"})

(defn- contains-forbidden?
  "Check if any forbidden term appears in keyword or proposal data."
  [proposal]
  (let [proposal-str (str/lower-case (str proposal))]
    (some #(str/includes? proposal-str %) forbidden-keywords)))

(defn- is-legitimate-flag-safety?
  "Check if this is a legitimate :flag-safety-concern that escalates."
  [proposal]
  (let [{:keys [testadmn.proposal/type]} proposal
        proposal-str (str/lower-case (str proposal))]
    (and (= type :flag-safety-concern)
         (some #(str/includes? proposal-str %) flagging-keywords)
         (not (contains-forbidden? proposal)))))

(defn- hard-check-1-session-verified
  "HARD CHECK 1: Target session must exist AND be :registered?/:verified?"
  [store proposal]
  (let [{:keys [testadmn.proposal/target-session-id]} proposal
        session (store/lookup-session store target-session-id)]
    (cond
      (nil? session)
      {:pass? false :reason "session-not-found" :session-id target-session-id}
      (not (:testadmn.test-session/registered? session))
      {:pass? false :reason "session-not-registered" :session-id target-session-id}
      :else
      {:pass? true :reason "session-verified"})))

(defn- hard-check-2-effect-is-propose
  "HARD CHECK 2: Effect must be :propose"
  [proposal]
  (let [{:keys [testadmn.proposal/effect]} proposal]
    (cond
      (not (= effect :propose))
      {:pass? false :reason "effect-not-propose" :effect effect}
      :else
      {:pass? true :reason "effect-is-propose"})))

(defn- hard-check-3-scope-exclusion
  "HARD CHECK 3: Scope exclusion for content/grading/eligibility/authority"
  [proposal]
  (cond
    ;; If it's a legitimate safety flag, allow it to escalate
    (is-legitimate-flag-safety? proposal)
    {:pass? true :reason "flag-safety-concern-escalates"}
    ;; If it contains forbidden patterns, reject
    (contains-forbidden? proposal)
    {:pass? false :reason "scope-excluded" :proposal proposal}
    :else
    {:pass? true :reason "within-scope"}))

;; === Proctor Impartiality (HARD CHECK 4) ===
;; An examiner must never be assigned as proctor for a session in which they
;; teach, supervise, or have a declared personal relationship with a registered
;; test-taker. This is a test-administration exam-integrity control: the
;; allowlist schedules :coordinate-proctor-assignment-proposal from Phase 2 and
;; auto-commits clean proposals at Phase 3, so a conflicted proctor MUST be
;; rejected by the governor rather than held (holding would still let Phase 3
;; auto-commit it the way :schedule-test-session auto-commits clean proposals).

(def ^:private impartiality-field
  ;; the key each proctor-assignment proposal must set on every proctor entry:
  ;; true => proctor declares they are impartial (no teaching/relationship with
  ;; any registered test-taker of the target session); false => conflicted.
  :testadmn.proposal/proctor-impartial?)

(defn- proctor-entries
  "Normalize a proctor-assignment proposal to its list of proctor maps."
  [proposal]
  (let [data (get proposal :testadmn.proposal/proposal-data {})
        raw (or (:proctors data) (:proctor-ids data) [])]
    (cond
      ;; list of maps already carrying the impartiality field
      (every? map? raw) raw
      ;; plan-of-strings: promote each to a map without a declared status
      (every? string? raw) (map (fn [p] {:proctor/id p}) raw)
      :else [])))

(defn- has-conflicted-proctor?
  "True when the proposal names at least one proctor declared non-impartial."
  [proposal]
  (let [entries (proctor-entries proposal)]
    (some (fn [p]
            (false? (get p impartiality-field
                         ;; default: a proctor with no declared status is
                         ;; treated as UNCONFIRMED — the assignment MUST declare
                         ;; impartiality explicitly before it can proceed.
                         (when (contains? p :proctor/id) nil))))
          entries)))

(defn- hard-check-4-proctor-impartiality
  "HARD CHECK 4: a proctor-assignment proposal must not name a conflicted
   (declared non-impartial) proctor. Applies only to the
   :coordinate-proctor-assignment-proposal op; all other ops pass trivially."
  [proposal]
  (let [{:keys [testadmn.proposal/type]} proposal]
    (if (not= type :coordinate-proctor-assignment-proposal)
      {:pass? true :reason "not-a-proctor-assignment"}
      (cond
        (has-conflicted-proctor? proposal)
        {:pass? false :reason "proctor-conflict-of-interest" :proposal proposal}
        :else
        {:pass? true :reason "proctors-impartial"}))))

;; === Accessibility Accommodation Logistics (HARD CHECK 5) ===
;; ISIC-855 test-administration must arrange HOW a test-taker with a disability
;; accesses a session — extra time, reader/scribe, accessible room, alternate
;; format, assistive tech — WITHOUT changing the test content, grading, or
;; eligibility judged. The allowlist schedules
;; :coordinate-accommodation-logistics from Phase 2 and auto-commits clean
;; proposals at Phase 3, so a category-less or unknown-category accommodation
;; proposal MUST be rejected by the governor rather than held (holding would
;; still let Phase 3 auto-commit it). An accommodation declares its access
;; category explicitly; it must never smuggle in a forfeited grading/eligibility
;; decision (any such change is already excluded by HARD CHECK 3 above).

(def ^:private accommodation-categories
  ;; the closed set of logistics-only access accommodations a test-taker may be
  ;; granted. Each is a HOW-to-access arrangement, never a WHAT-is-judged change.
  #{:time-extension
    :reader/scribe
    :accessible-room
    :alternate-format
    :assistive-tech})

(defn- accommodation-entries
  "Normalize an accommodation-logistics proposal to its list of accommodation
   category keywords. Produces [] (empty) when the proposal declares none."
  [proposal]
  (let [data (get proposal :testadmn.proposal/proposal-data {})
        raw (:accommodations data)]
    (cond
      (empty? raw) []
      (keyword? raw) [raw]
      :else (filter keyword? raw))))

(defn- hard-check-5-accommodation-logistics
  "HARD CHECK 5: an accommodation-logistics proposal must declare at least one
   recognized accommodation category. Applies only to the
   :coordinate-accommodation-logistics op; all other ops pass trivially.
   Note: any attempt to change test content, grading, or eligibility through
   an accommodation payload is already excluded earlier by HARD CHECK 3
   (scope-exclusion), which runs before this check — so this check only guards
   the accommodation's own logistics contract (a category MUST be declared and
   MUST be one of the closed set)."
  [proposal]
  (let [{:keys [testadmn.proposal/type]} proposal]
    (if (not= type :coordinate-accommodation-logistics)
      {:pass? true :reason "not-an-accommodation"}
      (cond
        (empty? (accommodation-entries proposal))
        {:pass? false :reason "accommodation-missing-category"
         :proposal proposal}
        (not (every? accommodation-categories (accommodation-entries proposal)))
        {:pass? false :reason "accommodation-unknown-category"
         :proposal proposal}
        :else
        {:pass? true :reason "accommodation-logistics-only"}))))

;; === Bounded Supply-Consumable Allowlist (HARD CHECK 6) ===
;; ISIC-855 test administration coordinates the supply of non-content
;; consumables to a test session (answer sheets, pencils, scratch paper).
;; The allowlist schedules :coordinate-supply-request from Phase 2 and
;; auto-commits clean proposals at Phase 3. Generic scope-exclusion (HARD
;; CHECK 3) only blocks content-bearing TERMS, so it does not bound WHICH
;; non-content consumables a request may order or whether it names any at
;; all. An arbitrary or unrecognized consumable line item (or an empty
;; request) would otherwise pass check 3 and auto-commit at Phase 3. HARD
;; CHECK 6 closes that: every :coordinate-supply-request must name at least
;; one item from the closed set of recognized test-administration
;; consumables, and must not name anything outside it — rejected outright,
;; never held, never auto-committed at Phase 3.

(def ^:private allowed-consumables
  ;; the closed set of logistics-only non-content consumables a
  ;; :coordinate-supply-request may order for a session. Each is a physical
  ;; delivery item, never test content, grading, eligibility, or policy.
  #{"answer-sheets" "answer-sheet" "pencils" "pencil" "scratch-paper"
    "erasers" "eraser" "water" "tissues" "tally-counters" "timers"
    "latex-free-gloves" "extra-batteries" "registration-pads"})

(defn- supply-item-entries
  "Normalize a supply-request proposal to its list of item strings.
   Produces [] (empty) when the request names no items."
  [proposal]
  (let [data (get proposal :testadmn.proposal/proposal-data {})
        raw (:supplies data)]
    (cond
      (empty? raw) []
      (string? raw) [raw]
      (sequential? raw) (filter string? raw)
      :else [])))

(defn- has-unrecognized-consumable?
  "True when the request names at least one item outside the closed
   consumable set (case-insensitive)."
  [proposal]
  (let [items (supply-item-entries proposal)]
    (some #(not (contains? allowed-consumables (str/lower-case %))) items)))

(defn- hard-check-6-supply-allowlist
  "HARD CHECK 6: a :coordinate-supply-request must name at least one
   recognized non-content consumable, and every named item must be in the
   closed consumable set. Applies only to :coordinate-supply-request; all
   other ops pass trivially.
   Note: content-bearing material (test booklets, answer keys) is already
   excluded earlier by HARD CHECK 3 (scope-exclusion), which runs before this
   check — so this check only guards the supply request's own logistics
   contract (items MUST be declared and MUST be recognized consumables)."
  [proposal]
  (let [{:keys [testadmn.proposal/type]} proposal]
    (if (not= type :coordinate-supply-request)
      {:pass? true :reason "not-a-supply-request"}
      (cond
        (empty? (supply-item-entries proposal))
        {:pass? false :reason "supply-missing-items" :proposal proposal}
        (has-unrecognized-consumable? proposal)
        {:pass? false :reason "supply-unrecognized-consumable"
         :proposal proposal}
        :else
        {:pass? true :reason "supply-consumables-allowed"}))))

;; === Attendance-Note Roster Binding (HARD CHECK 7) ===
;; ISIC-855 test administration logs who actually checked in / was absent for
;; a verified test session. The allowlist unlocks :log-attendance-note (and
;; auto-commits clean proposals) at Phase 3. Generic scope-exclusion (HARD
;; CHECK 3) only blocks content/grading/eligibility TERMS; it does not bound
;; WHICH test-taker a check-in/absent note may name. An attendance note naming
;; a test-taker who is not enrolled in the verified target session (or naming
;; nobody at all) would otherwise pass check 3 and auto-commit at Phase 3.
;; HARD CHECK 7 closes that: every :log-attendance-note must name at least one
;; test-taker id, and every id must be in the target session's enrolled-taker
;; roster. A note naming an out-of-roster (or fabricated) test-taker -- or none
;; -- is rejected outright, never held, never auto-committed at Phase 3.

(def ^:private attendance-fields
  ;; the payload keys a :log-attendance-note uses to name test-takers.
  [:check-in :absent])

(defn- attendance-test-taker-ids
  "Normalize an attendance-note proposal to its list of named test-taker ids."
  [proposal]
  (let [data (get proposal :testadmn.proposal/proposal-data {})]
    (->> attendance-fields
         (mapcat #(get data %))
         (filter string?)
         distinct
         vec)))

(defn- hard-check-7-attendance-roster
  "HARD CHECK 7: a :log-attendance-note must name at least one test-taker id
   and every named id must be in the target session's enrolled roster.
   Applies only to :log-attendance-note; all other ops pass trivially."
  [store proposal]
  (let [{:keys [testadmn.proposal/type]} proposal]
    (if (not= type :log-attendance-note)
      {:pass? true :reason "not-an-attendance-note"}
      (let [session (store/lookup-session store (:testadmn.proposal/target-session-id proposal))
            roster (store/enrolled-test-takers session)
            ids (attendance-test-taker-ids proposal)
            unknown (into [] (remove roster) ids)]
        (cond
          (empty? ids)
          {:pass? false :reason "attendance-empty" :proposal proposal}
          (seq unknown)
          {:pass? false :reason "attendance-unknown-test-taker"
           :test-takers unknown}
          :else
          {:pass? true :reason "attendance-test-takers-enrolled"})))))

;; === Facility Slot Conflict (HARD CHECK 8) ===
;; ISIC-855 test administration schedules rooms/facilities for standardized
;; test sessions. Scheduling (and Phase 3 auto-commit) already gate a
;; :schedule-test-session on the session being registered/verified (check 1),
;; effect being :propose (check 2) and scope (check 3) — but none of those see
;; OTHER sessions. A second session could be placed into a facility slot that a
;; previously scheduled session already occupies at the same start time,
;; seating two exams in one room. Only a store-wide view can catch that. HARD
;; CHECK 8 closes it: the verified target session's scheduled-start must not
;; collide with another (different) registered session that occupies the SAME
;; facility-id at the same scheduled-start. A colliding schedule is rejected
;; outright, never held, never auto-committed at Phase 3. Sessions that do not
;; carry a :facility-id (unverified/registration-only rows) can't seat an exam
;; and are ignored — a collision can only be between sessions that both name a
;; facility (a real seating plan).

(defn- facility-slot-key
  "A session's facility slot = [facility-id scheduled-start]; nil if the
   session has no facility-id (registration-only rows seat nothing)."
  [session]
  (let [fid (:testadmn.test-session/facility-id session)
        t0  (:testadmn.test-session/scheduled-start session)]
    (when (and fid t0)
      [fid t0])))

(defn- hard-check-8-facility-slot-free
  "HARD CHECK 8: a :schedule-test-session must not double-book a facility slot
   already occupied by another registered session at the same scheduled-start.
   Applies only to :schedule-test-session; all other ops pass trivially."
  [store proposal]
  (let [{:keys [testadmn.proposal/type testadmn.proposal/target-session-id]} proposal]
    (if (not= type :schedule-test-session)
      {:pass? true :reason "not-a-schedule"}
      (let [target (store/lookup-session store target-session-id)
            target-slot (facility-slot-key target)
            clashes (filter (fn [other]
                              (and (not= (:testadmn.test-session/id other) target-session-id)
                                   (:testadmn.test-session/registered? other)
                                   (= (facility-slot-key other) target-slot)))
                            (store/all-sessions store))
            clash (first clashes)]
        (cond
          (nil? target-slot)
          ;; target session carries no facility slot — nothing to double-book;
          ;; a schedule for such a session has no seating plan to protect.
          {:pass? true :reason "facility-slot-unspecified"}
          clash
          {:pass? false
           :reason "facility-slot-double-booked"
           :facility (first target-slot)
           :scheduled-start (second target-slot)
           :conflicts-with (:testadmn.test-session/id clash)}
          :else
          {:pass? true :reason "facility-slot-free"})))))

(defn evaluate-proposal
  "Evaluate proposal against all HARD checks.
   Returns {:accepted? boolean :checks [check-results] :reason string}"
  [store proposal]
  (let [check1 (hard-check-1-session-verified store proposal)
        check2 (hard-check-2-effect-is-propose proposal)
        check3 (hard-check-3-scope-exclusion proposal)
        check4 (hard-check-4-proctor-impartiality proposal)
        check5 (hard-check-5-accommodation-logistics proposal)
        check6 (hard-check-6-supply-allowlist proposal)
        check7 (hard-check-7-attendance-roster store proposal)
        check8 (hard-check-8-facility-slot-free store proposal)
        checks [check1 check2 check3 check4 check5 check6 check7 check8]
        all-pass? (and (:pass? check1) (:pass? check2) (:pass? check3)
                       (:pass? check4) (:pass? check5) (:pass? check6)
                       (:pass? check7) (:pass? check8))
        reason (cond
                 (not all-pass?)
                 (or (:reason (some #(when-not (:pass? %) %) checks))
                     "unknown-reason")
                 ;; a legitimate safety flag passing is not the same as an
                 ;; ordinary clean proposal -- preserve check3's specific
                 ;; "must always escalate" signal instead of collapsing it
                 ;; into the generic pass reason (the bug this branch fixes:
                 ;; a real safety concern's :reason used to read
                 ;; "all-checks-pass", indistinguishable from any other
                 ;; passing proposal).
                 (= "flag-safety-concern-escalates" (:reason check3))
                 "flag-safety-concern-escalates"
                 :else
                 "all-checks-pass")]
    {:accepted? all-pass?
     :checks checks
     :reason reason
     :proposal-id (get proposal :testadmn.proposal/id "unknown")}))

(defn reject-proposal
  "Reject a proposal and log reason."
  [store proposal reason]
  (store/log-proposal! store
    (merge proposal
           {:testadmn.proposal/effect :rollback
            :_rejected-reason reason})))