;; testadmn.store — Test Administration Logistics Coordination
;; Educational support activities (ISIC 855) — standardized test administration

(ns testadmn.store
  (:require #?(:clj  [clojure.spec.alpha :as s]
               :cljs [cljs.spec.alpha :as s])
            #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [langchain.db :as d]))

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

;; === Datomic-backed Store (langchain.db) ===
;; Same seam every sibling `cloud-itonami-isic-*` actor's store uses:
;; `MemStore` is the deterministic default (dev/tests/demo, no deps);
;; `DatomicStore` is backed by `langchain.db`, a Datomic-API-compatible EAV
;; store, and can be pointed at a real Datomic Local or a kotoba-server pod.
;; Both satisfy the SAME `TestAdmnStore` protocol and pass the same contract
;; (see `datomic-store-contract-test` in the test file), so the actor,
;; `testadmn.governor` and the audit trail never know which SSoT they run
;; on. Session fields (:testadmn.test-session/*) are already namespaced
;; Datomic-shaped keywords, so they transact/pull directly with no field
;; remapping; only the free-form `:testadmn.proposal/proposal-data` inside a
;; logged proposal needs the EDN-blob codec every sibling store uses.

(def ^:private schema
  {:testadmn.test-session/id {:db/unique :db.unique/identity}
   :proposal-log/seq         {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(def ^:private session-pull
  [:testadmn.test-session/id :testadmn.test-session/name
   :testadmn.test-session/registered? :testadmn.test-session/verified?
   :testadmn.test-session/scheduled-start :testadmn.test-session/facility-id])

(defn- prune-nils [m] (into {} (remove (comp nil? val) m)))

(defrecord DatomicStore [conn]
  TestAdmnStore
  (lookup-session [_this session-id]
    (let [m (prune-nils (d/pull (d/db conn) session-pull [:testadmn.test-session/id session-id]))]
      (when (:testadmn.test-session/id m) m)))
  (register-session! [_this session-id session-data]
    (d/transact! conn [(merge session-data
                              {:testadmn.test-session/id session-id
                               :testadmn.test-session/registered? true
                               :testadmn.test-session/verified? false})]))
  (create-session! [_this session-id session-data]
    (d/transact! conn [(merge {:testadmn.test-session/registered? false
                               :testadmn.test-session/verified? false}
                              session-data
                              {:testadmn.test-session/id session-id})]))
  (log-proposal! [this proposal]
    (d/transact! conn [{:proposal-log/seq (count (proposal-log this))
                        :proposal-log/record (enc proposal)}]))
  (proposal-log [_this]
    (->> (d/q '[:find ?s ?r :where [?e :proposal-log/seq ?s] [?e :proposal-log/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second)))))

(defn new-datomic-store
  "A DatomicStore (langchain.db backend), empty until sessions/proposals are
  registered/logged into it -- the Datomic-backed analog of
  `new-mem-store`, used to prove protocol parity."
  []
  (->DatomicStore (d/create-conn schema)))
