;; testadmn.store — Test Administration Logistics Coordination
;; Educational support activities (ISIC 855) — standardized test administration

(ns testadmn.store
  #?(:clj
     (:require [clojure.spec.alpha :as s]
               [langchain-store.core :as ls])
     :cljs
     (:require [clojure.spec.alpha :as s]
               [langchain-store.core :as ls])))

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
  (log-proposal! [_this proposal]
    (swap! proposals-atom conj proposal))
  (proposal-log [_this]
    @proposals-atom))

(defn new-mem-store []
  (->MemStore (atom {}) (atom [])))
