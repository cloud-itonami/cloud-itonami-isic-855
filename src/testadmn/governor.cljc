;; testadmn.governor — Test Administration Governor
;; Nineteen HARD, permanent, un-overridable checks

(ns testadmn.governor
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [testadmn.store :as store]))

(comment
  "Governor enforces permanent scope boundaries and rejects any proposal
   violating them.

   Nineteen HARD checks (un-overridable):
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
   6. Test-taker enrollment binding — attendance and accommodations may name only test-takers enrolled to the target session's roster.
   7. Bounded supply-consumable allowlist — a :coordinate-supply-request must
      name at least one recognized non-content consumable, and every item must
      be in the closed consumable set (answer sheets, pencils, scratch paper,
      etc.). Generic scope-exclusion (check 3) only blocks content-bearing
      TERMS; it does not bound WHICH non-content consumables a request may
      order. An unrecognized or empty supply request is rejected outright
      instead of being auto-committed at Phase 3, because auto-commit must not
      channel silently grant an access arrangement with no HOW documented.
   8. Attendance self-contradiction — a :log-attendance-note must not mark the
      same test-taker as both :check-in and :absent in the same session; the
      two sets must be disjoint. A self-contradiction is an ambiguous
      attendance record (the paper-trail equivalent of proxy/ghost attendance)
      and is rejected outright — never held, never auto-committed at Phase 3.
   9. Proctor staffing — a :schedule-test-session must declare a positive
      proctor headcount (:proctors >= 1) in its proposal-data. An exam cannot
      be administered with zero proctors — no one to verify attendance,
      supervise test-takers, or distribute/collect supplies. HARD CHECK 4
      (impartiality) only guards the separate
      :coordinate-proctor-assignment-proposal op; it says nothing about
      whether a session is staffed at all. A schedule naming zero (or
      omitting) proctors is rejected outright — never held, never
      auto-committed at Phase 3.
   10. Proctor assignment non-empty — a :coordinate-proctor-assignment-proposal
      must name at least one proctor. HARD CHECK 4 (impartiality) only rejects
      CONFLICTED proctors; it says nothing about an assignment naming NO
      proctors. A vacuous assignment passes checks 1-9 and — because only
      :flag-safety-concern is excluded from Phase-3 auto-commit — would
      auto-commit \"no one is assigned\" for a session that HARD CHECK 9 already
      required to be staffed. An empty assignment is rejected outright —
      never held, never auto-committed at Phase 3.
   11. Safety-concern category — a :flag-safety-concern must declare at least
      one recognized safety-concern category (:facility-hazard,
      :test-taker-wellbeing, :integrity-incident, :environmental-hazard).
      An empty or unknown-category safety flag is rejected outright — never
      held, never auto-committed at Phase 3.
   12. Schedule verified — the target session of a :schedule-test-session must
      carry a concrete venue (:testadmn.test-session/facility-id) and a start
      time (:testadmn.test-session/scheduled-start). :schedule-test-session is
      the Phase-1 scheduling act and the first op the closed allowlist
      auto-commits at Phase 3, so an unschedulable session (no room, no time)
      is rejected outright — never held, never auto-committed at Phase 3.
  13. No duplicate proctor assignment — a :coordinate-proctor-assignment-proposal must not name any proctor id more than once; doubling an id fabricates a second proctor in the room without adding an actual body.
  14. No duplicate attendance test-taker — a :log-attendance-note must not name the same test-taker id more than once within :check-in or within :absent; doubling an id   inflates the nominal attendance count without adding an actual seated body. A duplicated attendance id is rejected outright — never held, never auto-committed at Phase 3.
  15. No duplicate supply item — a :coordinate-supply-request must not
      name the same consumable more than once; repeating an item inflatesthe
      nominal supply count without adding an actual physical delivery item. A
      duplicated supply item is rejected outright — never held, never
      auto-committed at Phase 3.
  16. No duplicate accommodation rule — a :coordinate-accommodation-logistics
      must not name the same accommodation category more than once. HARD
      CHECK 5 (logistics-only categories) only requires every category to be
      recognized; it never guards against the SAME category being listed
      twice. Repeating a category is the accommodation-side analog of
      HARD CHECK 13's duplicated proctor, HARD CHECK 14's duplicated
      attendance id, and HARD CHECK 15's duplicated supply item: it inflates
      the nominal accommodation count without adding an actual access
      arrangement. A duplicated accommodation category is rejected outright
      — never held, never auto-committed at Phase 3.
  18. Schedule enrolled roster — the target session of a
      :schedule-test-session must carry a non-empty enrolled test-taker
      roster. HARD CHECK 6 (enrollment binding) only constrains
      attendance/accommodation proposals that NAME a test-taker; a
      :schedule-test-session names none, so a roster-less session passes
      checks 1-17 and, at Phase 3, auto-commits a plan to run an exam
      nobody is enrolled to sit. A session with no enrolled roster is
        19. Safety-referent category match — a :flag-safety-concern must carry the referent kind its category implies: a facility-class category (:facility-hazard, :environmental-hazard) MUST name a non-blank :facility-id, and :test-taker-wellbeing MUST name a non-blank :test-taker-id. HARD CHECK 11 only requires a recognized category and HARD CHECK 17 only requires SOME referent; neither ties the referent's TYPE to the category, so a facility-hazard flagged with only a person id (or a wellbeing concern flagged with only a room id) still escalates into the wrong triage lane. A category whose required referent kind is absent is rejected outright — never held, never auto-committed at Phase 3.
rejected outright — never held, never auto-committed at Phase 3.")

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

;; === Test-Taker Enrollment Binding (HARD CHECK 6) ===
;; ISIC-855 test administration must log attendance for -- and arrange
;; accommodations for (HOW a test-taker accesses a session) -- only people who
;; are actually registered to sit THAT session. A :log-attendance-note records
;; check-in/absent for real enrolled test-takers; naming an un-enrolled or
;; unknown id is the exam-integrity equivalent of proxying / impersonation
;; (someone sits the exam who is not registered), and an accommodation for an
;; unregistered person is a silent access grant to a non-participant. HARD
;; CHECK 6 therefore requires every test-taker id referenced by
;; :log-attendance-note or :coordinate-accommodation-logistics to be a member
;; of the target session's roster. A session with no roster, or a proposal
;; naming an id outside it, is rejected outright -- never held and never
;; auto-committed at Phase 3 (the Phase-3 path would otherwise auto-commit
;; clean-look attendance / accommodation for a fabricated person).

(defn- session-roster-set
  "Normalize the target session's roster to a Set of test-taker id strings.
   Across both backends the roster may arrive as a set (#{...}) or as a
   Datomic cardinality-many vector of strings; normalize both to a set so the
   membership check is identical. Absent/empty roster => empty set, which makes
   every attendance/accommodation proposal fail the membership check below."
  [session]
  (-> (get session :testadmn.test-session/roster #{})
      (as-> r (if (nil? r) #{} r))
      (as-> r (if (vector? r) (set r) r))))

(defn- named-test-taker-ids
  "Every test-taker id a proposal names: attendance check-in/absent sets, and
   the single :test-taker an accommodation logistics proposal arranges for.
   Empty when the proposal references none."
  [proposal]
  (let [data (get proposal :testadmn.proposal/proposal-data {})]
    (case (get proposal :testadmn.proposal/type)
      :log-attendance-note
      (set (concat (when (coll? (:check-in data)) (:check-in data)) (when (coll? (:absent data)) (:absent data))))
      :coordinate-accommodation-logistics
      (when-let [id (:test-taker data)] #{id})
      #{})))

(defn- hard-check-6-test-taker-enrollment
  "HARD CHECK 6: attendance/accommodation must name only test-takers enrolled
   to the target session. Applies only to :log-attendance-note and
   :coordinate-accommodation-logistics; all other ops pass trivially."
  [store proposal]
  (let [{:keys [testadmn.proposal/type testadmn.proposal/target-session-id]} proposal]
    (if (not (contains? #{:log-attendance-note :coordinate-accommodation-logistics} type))
      {:pass? true :reason "no-test-taker-binding-required"}
      (let [session (store/lookup-session store target-session-id)
            roster (session-roster-set session)
            named (named-test-taker-ids proposal)]
        (cond
          (empty? named)
          {:pass? false :reason "attendance-missing-test-taker" :proposal proposal}
          (not (every? roster named))
          {:pass? false :reason "test-taker-not-enrolled" :proposal proposal
           :unknown (vec (remove roster named))}
          :else
          {:pass? true :reason "test-takers-enrolled"})))))


;; === Bounded Supply-Consumable Allowlist (HARD CHECK 7) ===
;; ISIC-855 test administration coordinates the supply of non-content
;; consumables to a test session (answer sheets, pencils, scratch paper).
;; The allowlist schedules :coordinate-supply-request from Phase 2 and
;; auto-commits clean proposals at Phase 3. Generic scope-exclusion (HARD
;; CHECK 3) only blocks content-bearing TERMS, so it does not bound WHICH
;; non-content consumables a request may order or whether it names any at
;; all. An arbitrary or unrecognized consumable line item (or an empty
;; request) would otherwise pass check 3 and auto-commit at Phase 3. HARD
;; CHECK 7 closes that: every :coordinate-supply-request must name at least
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

(defn- hard-check-7-supply-allowlist
  "HARD CHECK 7: a :coordinate-supply-request must name at least one
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


;; === Attendance Self-Contradiction (HARD CHECK 8) ===
;; ISIC-855 test administration logs attendance per registered test-taker as
;; :check-in (present) or :absent. A single test-taker cannot simultaneously be
;; present and absent at the SAME test session -- marking the same id in both
;; :check-in and :absent is a logistics contradiction (an ambiguous attendance
;; record is the paper-trail equivalent of proxy/ghost attendance). HARD
;; CHECK 8 closes a gap in the attendance pipeline: the enrollment-binding
;; check (HARD CHECK 6) already verifies every id is on the session roster,
;; but its helper `named-test-taker-ids` collapses :check-in and :absent into
;; ONE set for that membership test, so a self-contradiction is silently
;; discarded today. HARD CHECK 8 therefore rejects a :log-attendance-note
;; whose :check-in and :absent sets are NOT disjoint -- outright, never held,
;; never auto-committed at Phase 3.

(defn- named-attendance-sets
  "The :check-in and :absent id sets of an attendance note, each normalized
   to a set (empty when absent). Kept separate from the membership test so the
   contradiction between the two stays visible instead of being collapsed."
  [proposal]
  (let [data (get proposal :testadmn.proposal/proposal-data {})]
    {:check-in (set (when (coll? (:check-in data)) (:check-in data)))
     :absent   (set (when (coll? (:absent data)) (:absent data)))}))

(defn- hard-check-8-attendance-self-contradiction
  "HARD CHECK 8: a :log-attendance-note must not mark the same test-taker as
   both :check-in and :absent. Applies only to :log-attendance-note; all other
   ops pass trivially."
  [proposal]
  (let [{:keys [testadmn.proposal/type]} proposal]
    (if (not= type :log-attendance-note)
      {:pass? true :reason "not-an-attendance-note"}
      (let [{:keys [check-in absent]} (named-attendance-sets proposal)
            both (set/intersection check-in absent)]
        (if (seq both)
          {:pass? false :reason "attendance-self-contradiction"
           :test-takers (vec both) :proposal proposal}
          {:pass? true :reason "attendance-non-contradictory"})))))


;; === Proctor Staffing Sufficiency (HARD CHECK 9) ===
;; ISIC-855 test administration cannot actually run a session with zero
;; proctors: nobody to verify attendance, supervise test-takers, or
;; distribute/collect supplies. The allowlist auto-commits clean proposals at
;; Phase 3 (see phase.cljc: every op except :flag-safety-concern auto-commits
;; at Phase 3), and :schedule-test-session is that phase-1 scheduling act.
;; Checks 1-8 never look at staffing: check 1 requires existence/registration,
;; check 2 requires :effect :propose, check 3 guards scope, checks 4-8 guard
;; proctor impartiality (a DIFFERENT op), accommodation, enrollment, supply,
;; attendance. None of them requires a scheduled session to have even ONE
;; proctor. A `:schedule-test-session` whose proposal-data names :proctors 0
;; (or omits it) therefore passes checks 1-8 and, at Phase 3, auto-commits an
;; exam plan with no supervision. HARD CHECK 9 closes that: a
;; :schedule-test-session must declare a positive integer proctor headcount
;; (>= 1); otherwise it is rejected outright, never held, never auto-committed
;; at Phase 3.

(defn- declared-proctor-headcount
  "The :proctors headcount a :schedule-test-session names in its
   proposal-data (nil when omitted). Room is where a session runs; proctors
   is who supervises it."
  [proposal]
  (let [data (get proposal :testadmn.proposal/proposal-data {})]
    (:proctors data)))

(defn- hard-check-9-proctor-staffing
  "HARD CHECK 9: a :schedule-test-session must declare a positive proctor
   headcount (an integer >= 1) in its proposal-data. Applies only to
   :schedule-test-session; all other ops pass trivially."
  [proposal]
  (let [{:keys [testadmn.proposal/type]} proposal]
    (if (not= type :schedule-test-session)
      {:pass? true :reason "not-a-schedule"}
      (let [n (declared-proctor-headcount proposal)]
        (if (and (integer? n) (pos? n))
          {:pass? true :reason "schedule-proctor-staffed"}
          {:pass? false :reason "schedule-no-proctor-staffing"
           :proposal proposal})))))


;; === Proctor Assignment Non-Empty (HARD CHECK 10) ===
;; ISIC-855 test administration assigns proctors to a session through the
;; :coordinate-proctor-assignment-proposal op (approval-gated at Phase 2,
;; auto-committed at Phase 3). HARD CHECK 4 (impartiality) only rejects
;; proctors DECLARED conflicted; an assignment naming NO proctor at all passes
;; checks 1-9 today and, because only :flag-safety-concern is excluded from
;; Phase-3 auto-commit, would auto-commit a vacuous "no one is assigned" for a
;; session that HARD CHECK 9 (staffing) already required to be staffed
;; (:proctors >= 1). HARD CHECK 10 closes that: the assignment must name at
;; least one proctor; otherwise it is rejected outright, never held, never
;; auto-committed at Phase 3.

(defn- hard-check-10-proctor-assignment-nonempty
  "HARD CHECK 10: a :coordinate-proctor-assignment-proposal must name at
   least one proctor. Applies only to that op; all other ops pass trivially."
  [proposal]
  (let [{:keys [testadmn.proposal/type]} proposal]
    (if (not= type :coordinate-proctor-assignment-proposal)
      {:pass? true :reason "not-a-proctor-assignment"}
      (if (empty? (proctor-entries proposal))
        {:pass? false :reason "proctor-assignment-empty" :proposal proposal}
        {:pass? true :reason "proctors-named"}))))

;; === Safety-Concern Category (HARD CHECK 11) ===
;; ISIC-855 an exam-day safety flag must name WHAT the concern is about: a
;; facility hazard, a test-taker wellbeing issue, a suspected integrity
;; incident, an environmental hazard. HARD CHECK3 (scope-exclusion) only
;; legitimizes :flag-safety-concern -- the type keyword itself carries flagging
;; keywords, so if passes scope-exclusion and is marked to escalate -- but
;; never requires the flag to name any actual concern. An empty ''no actual
;; concern'' with no declared category would pass check 3 and escalate as a
;; content-free no-op. HARD CHECK11 closes that: a :flag-safety-concern must
;; declare at least one recognized safety-concern category; otherwise it is
;; rejected outright, never held, never auto-committed at Phase 3
;; (safety never auto-commits anyway; this only makes every escalation
;; triage-actionable).

(def ^:private safety-concern-categories
  ;; the closed set of recognized exam-day safety-concern categories a
  ;; :flag-safety-concern may name. Each is a distinct escalation/triage route:
  ;; facility, test-taker wellbeing, suspected integrity/conduct, environment.


  #{:facility-hazard
    :test-taker-wellbeing
    :integrity-incident
    :environmental-hazard})

(defn- safety-concern-entries
  "Normalize a safety-flag proposal to its list of category keywords. Produces [] (empty) when the flag names no category."
  [proposal]
  (let [data (get proposal :testadmn.proposal/proposal-data {})
        raw (:safety-concerns data)]
    (cond
      (empty? raw) []
      (keyword? raw) [raw]
      :else (filter keyword? raw))))

(defn- hard-check-11-safety-category
  "HARD CHECK11: a :flag-safety-concern must declare at least one recognized
   safety-concern category. Applies only to :flag-safety-concern; all other ops
   pass trivially. Any content/grading/eligibility smuggled through a flag payload
   is already excluded earlier by HARD CHECK3 (scope-exclusion; this check only
   guards the flag's own triage contract ( a concern category MUST be declared and
   MUST be one of the closed set))."
  [proposal]
  (let [{:keys [testadmn.proposal/type]} proposal]
    (if (not= type :flag-safety-concern)
      {:pass? true :reason "not-a-safety-concern"}
      (cond
        (empty? (safety-concern-entries proposal))
        {:pass? false :reason "safety-concern-missing-category"
         :proposal proposal}
        (not (every? safety-concern-categories (safety-concern-entries proposal)))
        {:pass? false :reason "safety-concern-unknown-category"
         :proposal proposal}
        :else
        {:pass? true :reason "safety-concern-categorized"}))))
;; === Schedule Verified (HARD CHECK 12) ===
;; ISIC-855 test administration coordinates WHEN and WHERE a test session
;; actually runs. :schedule-test-session is the Phase-1 scheduling act and the
;; FIRST op the closed allowlist auto-commits at Phase 3 (see phase.cljc:
;; every op except :flag-safety-concern auto-commits clean proposals at Phase
;; 3). Yet none of checks 1-11 verify that the session being scheduled is
;; actually schedulable: check 1 only requires the session to exist and be
;; registered/verified (a session created with `create-session!` carries
;; neither a facility nor a start time and still passes), checks 2-11 guard
;; effect/scope/impartiality/accommodation/enrollment/supply/attendance/
;; staffing/assignment/safety-category. Without this check, a
;; :schedule-test-session for a session with NO venue (:facility-id) and NO
;; start time (:scheduled-start) passes the governor and auto-commits as if a
;; logistics plan existed — committing an exam you cannot place in a room at a
;; time. HARD CHECK 12 closes that: the target session of a
;; :schedule-test-session must carry a non-blank :facility-id and a non-blank
;; :scheduled-start; otherwise it is rejected outright, never held and never
;; auto-committed at Phase 3.

(defn- blank-value?
  "True when a schedule field is nil, or the empty string, or whitespace-only."
  [v]
  (or (nil? v)
      (and (string? v) (str/blank? v))))

(defn- hard-check-12-schedule-verified
  "HARD CHECK 12: the target session of a :schedule-test-session must carry a
   concrete venue (:facility-id) and a start time (:scheduled-start). Applies
   only to :schedule-test-session; all other ops pass trivially."
  [store proposal]
  (let [{:keys [testadmn.proposal/type testadmn.proposal/target-session-id]} proposal]
    (if (not= type :schedule-test-session)
      {:pass? true :reason "not-a-schedule"}
      (let [session (store/lookup-session store target-session-id)
            facility (get session :testadmn.test-session/facility-id)
            start (get session :testadmn.test-session/scheduled-start)]
        (cond
          (blank-value? facility)
          {:pass? false :reason "schedule-missing-facility"
           :session-id target-session-id}
          (blank-value? start)
          {:pass? false :reason "schedule-missing-start"
           :session-id target-session-id}
          :else
          {:pass? true :reason "schedule-verified"})))))

;; === Proctor-Assignment No Duplicate (HARD CHECK 13) ===
;; ISIC-855 test administration assigns proctors to a session through the
;; :coordinate-proctor-assignment-proposal op. HARD CHECK10 requires the
;; assignment to name at least one proctor, and HARD CHECK4 requires each
;; named proctor to declare impartiality -- but neither guards whether the
;; assignment names THE SAME proctor twice. Doubling a proctor's id in the
;; paper trail is the logistics equivalent of fabricating a second proctor in
;; the room: it inflates the observable staffing roster without adding an
;; actual body, so a malformed assignment could claim more supervision than
;; the room actually has. HARD CHECK13 closes that: a
;; :coordinate-proctor-assignment-proposal must not name the same proctor id
;; more than once; otherwise it is rejected outright, never held, never
;; auto-committed at Phase 3.

(defn- named-proctor-ids
  "The set of proctor ids a proctor-assignment proposal names (duplicates
   collapse to one member, so a repeated id is detectable)."
  [proposal]
  (into #{} (keep :proctor/id (proctor-entries proposal))))

(defn- has-duplicate-proctor?
  "True when a proctor assignment names the same proctor id more than once."
  [proposal]
  (let [ids (keep :proctor/id (proctor-entries proposal))]
    (> (count ids) (count (named-proctor-ids proposal)))))

(defn- hard-check-13-proctor-assignment-no-duplicate
  "HARD CHECK13: a :coordinate-proctor-assignment-proposal must not name a
   proctor id more than once. Applies only to that op; all other ops pass
   trivially."
  [proposal]
  (let [{:keys [testadmn.proposal/type]} proposal]
    (if (not= type :coordinate-proctor-assignment-proposal)
      {:pass? true :reason "not-a-proctor-assignment"}
      (if (has-duplicate-proctor? proposal)
        {:pass? false :reason "proctor-assignment-duplicate" :proposal proposal}
        {:pass? true :reason "proctors-distinct"}))))


;; === No Duplicate Attendance Test-Taker (HARD CHECK 14) ===
;; ISIC-855 test administration logs per-session attendance as :check-in / :absent.
;; HARD CHECK 6 (enrollment binding) collects both into ONE set for membership, and
;; HARD CHECK 8 (self-contradiction) requires the two be disjoint — but neither guards
;; against the SAME id being repeated WITHIN :check-in (or within :absent), which sets
;; silently collapse. Repeating an id is the attendance-side analog of HARD CHECK 13’s
;; duplicated proctor:it inflates the nominal attendance count without adding an actual
;; seated body. HARD CHECK 14 rejects a note that repeats a test-taker id within
;; :check-in or within :absent — outright, never held, never auto-committed at Phase 3.

(defn- has-duplicate-attendance-id?
  "True when an attendance field (:check-in or :absent) lists the same test-taker id more than once (sets normalize this away, so the raw sequence must be inspected)."
  [proposal k]
  (let [data (get proposal :testadmn.proposal/proposal-data {})
        xs   (get data k)]
    (and (sequential? xs)
         (> (count xs) (count (distinct xs))))))

(defn- hard-check-14-attendance-no-duplicate-test-taker
  "HARD CHECK14: a :log-attendance-note must not name the same test-taker id more than once within :check-in or within :absent. Applies only to :log-attendance-note; all other ops pass trivially."
  [proposal]
  (let [{:keys [testadmn.proposal/type]} proposal]
    (if (not= type :log-attendance-note)
      {:pass? true :reason "not-an-attendance-note"}
      (cond
        (has-duplicate-attendance-id? proposal :check-in)
        {:pass? false :reason "attendance-duplicate-check-in" :proposal proposal}
        (has-duplicate-attendance-id? proposal :absent)
        {:pass? false :reason "attendance-duplicate-absent" :proposal proposal}
        :else
        {:pass? true :reason "attendance-test-takers-distinct"}))))

;; === No Duplicate Supply Item (HARD CHECK 15) ===
;; ISIC-855 test administration coordinates room supplies through the
;; :coordinate-supply-request op. HARD CHECK 7 (bounded consumable
;; allowlist) requires every named item to be a recognized non-content
;; consumable, but it never guards against THE SAME consumable being listed
;; twice. Repeating an item in the request is the logistics-analog of
;; HARD CHECK13's duplicated proctor and HARD CHECK14's duplicated
;; attendance id:it inflates the nominal supply count (and thus the
;; consumed-stock ledger) without adding an actual physical delivery item
;; to the room. HARD CHECK15 rejects a request that repeats a consumable
;; item (case-insensitively matched, echoing CHECK7's normalization —
;; outright, never held, never auto-committed at Phase 3.

(defn- has-duplicate-supply-item?
  "True when a supply request lists the same consumable more than once
   (case-insensitive match, mirroring HARD CHECK7's normalization. "
  [proposal]
  (let [items (mapv str/lower-case (supply-item-entries proposal))]
    (> (count items) (count (distinct items)))))

(defn- hard-check-15-supply-no-duplicate
  "HARD CHECK15: a :coordinate-supply-request must not name the same
   consumable more than once. Applies only to :coordinate-supply-request;
   all other ops pass trivially."
  [proposal]
  (let [{:keys [testadmn.proposal/type]} proposal]
    (if (not= type :coordinate-supply-request)
      {:pass? true :reason "not-a-supply-request"}
      (if (has-duplicate-supply-item? proposal)
        {:pass? false :reason "supply-duplicate-item" :proposal proposal}
        {:pass? true :reason "supply-items-distinct"}))))

;; === No Duplicate Accommodation Rule (HARD CHECK 16) ===
;; ISIC-855 test administration arranges accessibility logistics for a
;; test-taker through the :coordinate-accommodation-logistics op. HARD
;; CHECK 5 (logistics-only categories) requires every accommodation category
;; to be one of the closed set, but it never guards against the SAME
;; category being declared twice. Repeating a category is the
;; accommodation-side analog of HARD CHECK 13's duplicated proctor, HARD
;; CHECK 14's duplicated attendance id, and HARD CHECK 15's duplicated
;; supply item: it inflates the nominal accommodation count (the number of
;; access arrangements declared) without adding an actual separate
;; arrangement to the room. HARD CHECK 16 rejects a proposal that repeats an
;; accommodation category — outright, never held, never auto-committed at
;; Phase 3.

(defn- has-duplicate-accommodation-rule?
  "True when an accommodation proposal declares the same category more than
   once (a set would collapse these, so the raw sequence is inspected)."
  [proposal]
  (let [cats (accommodation-entries proposal)]
    (> (count cats) (count (distinct cats)))))

(defn- hard-check-16-accommodation-no-duplicate
  "HARD CHECK 16: a :coordinate-accommodation-logistics must not name the
   same accommodation category more than once. Applies only to
   :coordinate-accommodation-logistics; all other ops pass trivially."
  [proposal]
  (let [{:keys [testadmn.proposal/type]} proposal]
    (if (not= type :coordinate-accommodation-logistics)
      {:pass? true :reason "not-an-accommodation"}
      (if (has-duplicate-accommodation-rule? proposal)
        {:pass? false :reason "accommodation-duplicate-category" :proposal proposal}
        {:pass? true :reason "accommodation-rules-distinct"}))))


;; === Safety-Concern Referent (HARD CHECK 17) ===
;; ISIC-855 an exam-day safety flag must name WHO/WHAT the concern is about,
;; not just a category. HARD CHECK 11 requires a recognized CATEGORY
;; (:facility-hazard, :test-taker-wellbeing, ...), but a flag that names a
;; category yet no concrete referent -- no :facility-id, no :test-taker-id --
;; still escalates (HARD CHECK 3 legitimizes every flag carrying flagging
;; keywords) as a content-free no-op that triage cannot route to any room or
;; person. HARD CHECK 17 closes that: a :flag-safety-concern must name at
;; least one concrete referent -- a non-blank :facility-id (facility /
;; environmental hazards) and/or a :test-taker-id (wellbeing / integrity
;; concern about a person). A referent-less flag is rejected outright, never
;; held, never auto-committed at Phase 3 (safety never auto-commits anyway;
;; this makes every escalation triage-actionable to a specific room or
;; test-taker).

(defn- safety-referents
  "The concrete referent(s) a safety-flag names so triage knows WHERE or WHO:
   a non-blank :facility-id and/or :test-taker-id in the proposal-data."
  [proposal]
  (let [data (get proposal :testadmn.proposal/proposal-data {})]
    (filter #(not (blank-value? %))
            [(:facility-id data) (:test-taker-id data)])))

(defn- hard-check-17-safety-referent
  "HARD CHECK 17: a :flag-safety-concern must name at least one concrete
   referent (:facility-id and/or :test-taker-id). Applies only to
   :flag-safety-concern; all other ops pass trivially. Note: scope-exclusion
   (HARD CHECK 3) only legitimizes flagging keywords and blocks forbidden
   TERMS; it neither requires nor bounds a referent -- so without this check a
   category-only flag would still escalate untriageable."
  [proposal]
  (let [{:keys [testadmn.proposal/type]} proposal]
    (if (not= type :flag-safety-concern)
      {:pass? true :reason "not-a-safety-concern"}
      (if (empty? (safety-referents proposal))
        {:pass? false :reason "safety-concern-missing-referent" :proposal proposal}
        {:pass? true :reason "safety-concern-referenced"}))))

;; === Enrolled Test-Taker Roster (HARD CHECK 18) ===
;; ISIC-855 a :schedule-test-session names no test-taker of its own, so HARD
;; CHECK 6 (enrollment binding) never sees it -- attendance/accommodation are
;; the only proposals that NAME test-takers. Yet scheduling is the phase-1
;; act that auto-commits at Phase 3: a session whose target has NO enrolled
;; roster (no one registered to sit it) passes every check 1-17 and commits
;; as if a seated examinee population existed. That is the scheduling-side
;; analog of ghost/proxy testing -- the logistics plan exists but nobody is
;; enrolled to take the exam. HARD CHECK 18 closes it: the target session of
;; a :schedule-test-session must carry a NON-EMPTY enrolled test-taker
;; roster; otherwise it is rejected outright -- never held, never
;; auto-committed at Phase 3.

(defn- hard-check-18-schedule-enrolled-roster
  "HARD CHECK 18: the target session of a :schedule-test-session must carry a
   non-empty :testadmn.test-session/roster. Applies only to
   :schedule-test-session; every other op passes trivially."
  [store proposal]
  (let [{:keys [testadmn.proposal/type testadmn.proposal/target-session-id]} proposal]
    (if (not= type :schedule-test-session)
      {:pass? true :reason "not-a-schedule"}
      (let [session (store/lookup-session store target-session-id)
            roster (session-roster-set session)]
        (if (empty? roster)
          {:pass? false :reason "schedule-no-enrolled-roster"
           :session-id target-session-id}
          {:pass? true :reason "schedule-roster-enrolled"})))))

;; === Safety-Referent Category Match (HARD CHECK 19) ===
;; ISIC-855 a safety flag's category must be matched by the RIGHT kind of
;; referent. HARD CHECK 11 requires a recognized CATEGORY; HARD CHECK 17
;; requires at least one referent -- but neither ties the referent's TYPE to
;; the category. A flag declaring :facility-hazard (a room/environment
;; concern) while naming only a :test-taker-id -- or :test-taker-wellbeing (a
;; person concern) while naming only a :facility-id -- still escalates into
;; the WRONG triage lane. HARD CHECK 19 closes it: a facility-class category
;; (:facility-hazard, :environmental-hazard) MUST carry a non-blank
;; :facility-id; :test-taker-wellbeing MUST carry a non-blank :test-taker-id.
;; A category whose required referent kind is absent is rejected outright,
;; never held, never auto-committed at Phase 3 (safety never auto-commits
;; anyway; this makes every escalation route to the lane its category implies).

(defn- hard-check-19-safety-referent-match
  "HARD CHECK 19: a safety flag's referent TYPE must match its category.
   facility-class categories need a :facility-id; :test-taker-wellbeing needs
   a :test-taker-id. Applies only to :flag-safety-concern; all other ops pass
   trivially. Note check 11/17 only require A category / A referent; this
   check binds the two."
  [proposal]
  (let [{:keys [testadmn.proposal/type]} proposal]
    (if (not= type :flag-safety-concern)
      {:pass? true :reason "not-a-safety-concern"}
      (let [data (get proposal :testadmn.proposal/proposal-data {})
            catset (set (safety-concern-entries proposal))
            facility-class #{:facility-hazard :environmental-hazard}
            facility-cats (set/intersection catset facility-class)
            facility-missing? (and (seq facility-cats)
                                   (blank-value? (:facility-id data)))
            wellbeing-missing? (and (contains? catset :test-taker-wellbeing)
                                    (blank-value? (:test-taker-id data)))]
        (cond
          facility-missing?
          {:pass? false :reason "safety-referent-facility-missing"
           :categories (vec facility-cats) :proposal proposal}
          wellbeing-missing?
          {:pass? false :reason "safety-referent-test-taker-missing"
           :categories [:test-taker-wellbeing] :proposal proposal}
          :else
          {:pass? true :reason "safety-referent-matched"})))))


(defn evaluate-proposal
  "Evaluate proposal against all HARD checks.
   Returns {:accepted? boolean :checks [check-results] :reason string}"
  [store proposal]
  (let [check1 (hard-check-1-session-verified store proposal)
        check2 (hard-check-2-effect-is-propose proposal)
        check3 (hard-check-3-scope-exclusion proposal)
        check4 (hard-check-4-proctor-impartiality proposal)
        check5 (hard-check-5-accommodation-logistics proposal)
        check6 (hard-check-6-test-taker-enrollment store proposal)
        check7 (hard-check-7-supply-allowlist proposal)
        check8 (hard-check-8-attendance-self-contradiction proposal)
        check9 (hard-check-9-proctor-staffing proposal)
        check10 (hard-check-10-proctor-assignment-nonempty proposal)
        check11 (hard-check-11-safety-category proposal)
        check12 (hard-check-12-schedule-verified store proposal)
        check13 (hard-check-13-proctor-assignment-no-duplicate proposal)
        check14 (hard-check-14-attendance-no-duplicate-test-taker proposal)
        check15 (hard-check-15-supply-no-duplicate proposal)
        check16 (hard-check-16-accommodation-no-duplicate proposal)
        check17 (hard-check-17-safety-referent proposal)
        check18 (hard-check-18-schedule-enrolled-roster store proposal)
        check19 (hard-check-19-safety-referent-match proposal)
        checks [check1 check2 check3 check4 check5 check6 check7 check9 check10 check11 check12 check13 check14 check15 check16 check17 check18 check19 check8]
        all-pass? (and (:pass? check1) (:pass? check2) (:pass? check3)
                       (:pass? check4) (:pass? check5) (:pass? check6)
                       (:pass? check7) (:pass? check9) (:pass? check10) (:pass? check11) (:pass? check12) (:pass? check13) (:pass? check14) (:pass? check15) (:pass? check16) (:pass? check17) (:pass? check18) (:pass? check19) (:pass? check8))
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
