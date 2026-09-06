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
(s/def :testadmn.test-session/roster (s/coll-of string? :kind set?))

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
  {:testadmn.test-session/id      {:db/unique :db.unique/identity}
   :testadmn.test-session/roster  {:db/cardinality :db.cardinality/many}
   :proposal-log/seq              {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(def ^:private session-pull
  [:testadmn.test-session/id :testadmn.test-session/name
   :testadmn.test-session/registered? :testadmn.test-session/verified?
   :testadmn.test-session/scheduled-start :testadmn.test-session/facility-id
   :testadmn.test-session/roster])

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

;; === Demo seed ===
;; The single named seed the demo surfaces read from, so that
;; `testadmn.render-html` renders REAL store state instead of hand-typed
;; rows. `session-001`'s field values are carried over verbatim from this
;; repo's own pre-existing demo driver `testadmn.sim/simulate-session`
;; (name "SAT Administration 2026-07-15", scheduled-start
;; "2026-07-15T09:00:00Z", facility "facility-101"); the remaining entries
;; exist to make each of `governor`'s three HARD checks reachable against
;; real ground-truth store state rather than a synthetic proposal:
;;
;;   session-002  registered   -- clean target for scope-exclusion / effect holds
;;   session-003  UNregistered -- created via `create-session!`, the entry point
;;                               whose docstring above exists precisely so
;;                               `hard-check-1`'s "session-not-registered" branch
;;                               is reachable through the public API
;;   session-009  ABSENT       -- deliberately never seeded, so "session-not-found"
;;                               is exercised by a genuinely missing record and
;;                               not by a fabricated one
;;
;; `sim` is intentionally left untouched (it seeds its own session inline);
;; this seed is additive.

(def demo-registered-sessions
  "Sessions seeded through `register-session!` (which forces :registered? true,
  :verified? false -- see the deftype above). Each carries its live test-taker
  roster -- the enrolled-taker set HARD CHECK 6 reads to verify that no
  attendance or accommodation proposal names a person who is not registered to
  sit THAT session (anti-impersonation / anti-proxy-testing control)."
  [{:testadmn.test-session/id "session-001"
    :testadmn.test-session/name "SAT Administration 2026-07-15"
    :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
    :testadmn.test-session/facility-id "facility-101"
    :testadmn.test-session/roster #{"T. Nakagawa" "A. Yamada" "R. Ito"}}
   {:testadmn.test-session/id "session-002"
    :testadmn.test-session/name "ACT Administration 2026-07-22"
    :testadmn.test-session/scheduled-start "2026-07-22T09:00:00Z"
    :testadmn.test-session/facility-id "facility-204"
    :testadmn.test-session/roster #{"K. Tanaka" "S. Suzuki" "M. Kato"}}])

(def demo-unregistered-sessions
  "Sessions seeded through `create-session!`, left :registered? false on
  purpose -- ground truth for `governor/hard-check-1`'s second branch."
  [{:testadmn.test-session/id "session-003"
    :testadmn.test-session/name "AP Chemistry Administration 2026-08-03"
    :testadmn.test-session/scheduled-start "2026-08-03T13:00:00Z"
    :testadmn.test-session/facility-id "facility-101"}])

(def demo-absent-session-id
  "An id deliberately NOT seeded, so `hard-check-1`'s \"session-not-found\"
  branch is driven by a real absent lookup."
  "session-009")

(def demo-rooms
  "Room label and proctor headcount the demo proposes for each session, so
  that every room identifier a demo surface displays has a named source in
  this repo rather than being typed inline by a renderer. `session-001`'s
  values (\"Gym A\", 3 proctors) are this repo's own pre-existing figures
  from `testadmn.sim/simulate-session`; the rest follow the same shape for
  the sessions added above.

  This is scheduling PROPOSAL input, not session ground truth -- it is
  deliberately NOT part of the `:testadmn/test-session` record, which the
  governor reads as ground truth."
  {"session-001" {:room "Gym A"    :proctors 3}
   "session-002" {:room "Hall B"   :proctors 4}
   "session-003" {:room "Lab C"    :proctors 2}
   "session-009" {:room "Annex D"  :proctors 2}})

(def demo-proctor-ids
  "The proctor ids `testadmn.sim/simulate-session` proposes for a proctor
  assignment, reused verbatim so the demo surfaces show the same ids."
  ["p1" "p2" "p3"])

(defn demo-session-ids
  "Every session id the demo seed touches, in a stable render order --
  including `demo-absent-session-id`, which by construction has no record."
  []
  (concat (map :testadmn.test-session/id demo-registered-sessions)
          (map :testadmn.test-session/id demo-unregistered-sessions)
          [demo-absent-session-id]))

(defn seed-demo-store!
  "Seeds `store` with the demo sessions above and returns it. Uses only the
  public protocol, so it seeds a MemStore and a DatomicStore identically."
  [store]
  (doseq [{:testadmn.test-session/keys [id] :as m} demo-registered-sessions]
    (register-session! store id (dissoc m :testadmn.test-session/id)))
  (doseq [{:testadmn.test-session/keys [id] :as m} demo-unregistered-sessions]
    (create-session! store id (dissoc m :testadmn.test-session/id)))
  store)

(defn seed-demo-store
  "A fresh `new-mem-store` with the demo seed already applied."
  []
  (seed-demo-store! (new-mem-store)))
