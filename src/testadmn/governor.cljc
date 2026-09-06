;; testadmn.governor — Test Administration Governor
;; Four HARD, permanent, un-overridable checks

(ns testadmn.governor
  (:require [clojure.string :as str]
            [testadmn.store :as store]))

(comment
  "Governor enforces permanent scope boundaries and rejects any proposal
   violating them.

   Four HARD checks (un-overridable):
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
      closed allowlist schedules at Phase 2+ and MUST NOT auto-commit at Phase 3.")

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

(defn evaluate-proposal
  "Evaluate proposal against all HARD checks.
   Returns {:accepted? boolean :checks [check-results] :reason string}"
  [store proposal]
  (let [check1 (hard-check-1-session-verified store proposal)
        check2 (hard-check-2-effect-is-propose proposal)
        check3 (hard-check-3-scope-exclusion proposal)
        check4 (hard-check-4-proctor-impartiality proposal)
        checks [check1 check2 check3 check4]
        all-pass? (and (:pass? check1) (:pass? check2) (:pass? check3) (:pass? check4))
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