;; testadmn.store — Test Administration Logistics Coordination
;; Educational support activities (ISIC 855) — standardized test administration

(ns testadmn.store
  ;; langchain-store.core (kotoba-lang/langchain-store, :dev-only dep in
  ;; deps.edn) is not required here yet -- this store is still MemStore-only,
  ;; unlike the sibling ISIC-85 actors' Datomic-backed second store. Add the
  ;; require when that wiring is actually built, not before (an unused
  ;; require was here previously and flagged by clj-kondo).
  (:require [clojure.spec.alpha :as s]))

(comment
  "Educational testing/exam administration logistics coordination.

   Scope: Standardized test session scheduling, proctor assignment proposals,
   answer-sheet supply coordination, attendance logging, integrity concerns.

   Out of scope: test content decisions, grading, eligibility determinations,
   academic policy, safety authority overrides.")

;; === Schema & Identity ===

(s/def :testadmn.test-session/id string?)
(s/def :testadmn.test-session/name string?)
(s/def :testadmn.test-session/registered? boolean?)
(s/def :testadmn.test-session/verified? boolean?)
(s/def :testadmn.test-session/scheduled-start #?(:clj inst? :cljs string?))
(s/def :testadmn.test-session/facility-id string?)

(s/def :testadmn/test-session
  (s/keys :req [:testadmn.test-session/id
                :testadmn.test-session/name
                :testadmn.test-session/registered?
                :testadmn.test-session/verified?]))

;; === Propositions ===

(s/def :testadmn.proposal/id string?)
(s/def :testadmn.proposal/type keyword?)
(s/def :testadmn.proposal/effect #{:propose :hold :commit :rollback})
(s/def :testadmn.proposal/target-session-id string?)
(s/def :testadmn.proposal/proposal-data (s/map-of keyword? any?))

(s/def :testadmn/proposal
  (s/keys :req [:testadmn.proposal/id
                :testadmn.proposal/type
                :testadmn.proposal/effect
                :testadmn.proposal/target-session-id]))

;; === In-Memory Store Implementation ===

(defprotocol TestAdmnStore
  "Store interface for test administration operations."
  (lookup-session [store session-id])
  (register-session! [store session-id session-data])
  (create-session! [store session-id session-data]
    "Create a session record with EXPLICIT :registered?/:verified? flags
    taken from `session-data` (both default false when omitted) -- unlike
    `register-session!`, does NOT force :registered? true. This is how an
    existing-but-not-yet-registered (or registered-but-unverified) session
    is constructed, the ground-truth state `governor/hard-check-1` needs to
    exercise its own \"session-not-registered\" branch (a branch that was
    previously unreachable through the public API: `register-session!` was
    the only session-creation entry point and it unconditionally set
    :registered? true, so nothing could ever reach that branch).")
  (log-proposal! [store proposal])
  (proposal-log [store]))

(deftype MemStore [sessions-atom proposals-atom]
  TestAdmnStore
  (lookup-session [_this session-id]
    (@sessions-atom session-id))
  (register-session! [_this session-id session-data]
    (swap! sessions-atom assoc session-id
      (merge session-data
             {:testadmn.test-session/id session-id
              :testadmn.test-session/registered? true
              :testadmn.test-session/verified? false})))
  (create-session! [_this session-id session-data]
    (swap! sessions-atom assoc session-id
      (merge {:testadmn.test-session/registered? false
              :testadmn.test-session/verified? false}
             session-data
             {:testadmn.test-session/id session-id})))
  (log-proposal! [_this proposal]
    (swap! proposals-atom conj proposal))
  (proposal-log [_this]
    @proposals-atom))

(defn new-mem-store []
  (->MemStore (atom {}) (atom [])))
