;; testadmn.phase — Test Administration Actor Phases
;; Staged rollout: Phase 0 → 3

(ns testadmn.phase
  (:require #?(:clj  [clojure.spec.alpha :as s]
               :cljs [cljs.spec.alpha :as s])))

(comment
  "Phase 0: read-only.
   Phase 1: schedule test session (approval-gated).
   Phase 2: + proctor assignment, supply coordination, accessibility
            accommodation logistics (approval-gated).
   Phase 3: auto-commits clean proposals; safety concerns always escalate.")

(s/def :testadmn.phase/stage #{0 1 2 3})

(defn allowed-operations
  "Return set of allowed operations for a given phase."
  [phase-num]
  (case phase-num
    0 #{}  ; read-only -- including safety flagging; phase 0 means no proposals at all
    1 #{:schedule-test-session
        :flag-safety-concern}
    2 #{:schedule-test-session
        :coordinate-proctor-assignment-proposal
        :coordinate-supply-request
        :coordinate-accommodation-logistics
        :flag-safety-concern}
    3 #{:schedule-test-session
        :coordinate-proctor-assignment-proposal
        :coordinate-supply-request
        :coordinate-accommodation-logistics
        :log-attendance-note
        :flag-safety-concern}
    #{:flag-safety-concern}))  ; fallback: always allow safety

(defn is-allowed?
  "Check if operation is allowed in current phase."
  [phase-num op-type]
  (contains? (allowed-operations phase-num) op-type))

(defn should-auto-commit?
  "In Phase 3, auto-commit clean proposals (except safety concerns)."
  [phase-num op-type]
  (and (= phase-num 3)
       (not= op-type :flag-safety-concern)))