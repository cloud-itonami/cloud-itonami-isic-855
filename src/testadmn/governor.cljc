;; testadmn.governor — Test Administration Governor
;; Three HARD, permanent, un-overridable checks

(ns testadmn.governor
  (:require [clojure.string :as str]
            [testadmn.store :as store]))

(comment
  "Governor enforces permanent scope boundaries and rejects any proposal
   violating them.

   Three HARD checks (un-overridable):
   1. Test-session verified — target must exist in store AND be :registered?/:verified?
   2. Effect is :propose — any other :effect value is rejected outright
   3. Scope exclusion — test-content, scoring, eligibility, academic-integrity
      adjudication, or safety-authority overrides are blocked. Legitimate
      :flag-safety-concern escalates only, never auto-commits.")

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

(defn evaluate-proposal
  "Evaluate proposal against all three HARD checks.
   Returns {:accepted? boolean :checks [check-results] :reason string}"
  [store proposal]
  (let [check1 (hard-check-1-session-verified store proposal)
        check2 (hard-check-2-effect-is-propose proposal)
        check3 (hard-check-3-scope-exclusion proposal)
        all-pass? (and (:pass? check1) (:pass? check2) (:pass? check3))
        checks [check1 check2 check3]
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
