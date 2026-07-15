;; testadmn.operation — Test Administration Operations
;; Closed :propose-only op allowlist

(ns testadmn.operation
  (:require [clojure.spec.alpha :as s]
            [testadmn.store :as store]
            [testadmn.governor :as gov]
            [testadmn.phase :as phase]))

(comment
  "Closed :propose-only allowlist:
   1. :schedule-test-session — exam session scheduling/room logistics
   2. :coordinate-proctor-assignment-proposal — administrative proctor PROPOSAL
   3. :coordinate-supply-request — non-content consumables
   4. :log-attendance-note — test-session attendance/check-in logging
   5. :flag-safety-concern — facility/integrity/wellbeing concerns")

;; === Operation Types ===

(def allowed-ops
  #{:schedule-test-session
    :coordinate-proctor-assignment-proposal
    :coordinate-supply-request
    :log-attendance-note
    :flag-safety-concern})

(s/def :testadmn.operation/type (set allowed-ops))

;; === Operation Dispatcher ===

(defprotocol TestAdmnOperation
  "Interface for executing test administration operations."
  (execute [operation store phase-num]))

(defn make-operation
  "Create an operation record."
  [op-type target-session-id proposal-data]
  {:testadmn.proposal/id (str "op-" (System/nanoTime))
   :testadmn.proposal/type op-type
   :testadmn.proposal/effect :propose
   :testadmn.proposal/target-session-id target-session-id
   :testadmn.proposal/proposal-data proposal-data})

(defn execute-operation
  "Execute operation: validate → governor checks → commit/reject."
  [operation store phase-num]
  (let [{:keys [testadmn.proposal/type]} operation
        phase-check (phase/is-allowed? phase-num type)
        gov-result (gov/evaluate-proposal store operation)
        should-commit? (and phase-check (:accepted? gov-result) (phase/should-auto-commit? phase-num type))]
    (cond
      (not phase-check)
      {:status :rejected
       :reason "operation-not-allowed-in-phase"
       :phase phase-num
       :operation-type type}
      (not (:accepted? gov-result))
      {:status :rejected
       :reason (:reason gov-result)
       :checks (:checks gov-result)}
      should-commit?
      (do (store/log-proposal! store
            (merge operation {:testadmn.proposal/effect :commit}))
          {:status :auto-committed
           :operation-id (get operation :testadmn.proposal/id)})
      (= type :flag-safety-concern)
      (do (store/log-proposal! store
            (merge operation {:testadmn.proposal/effect :escalate}))
          {:status :escalated
           :reason "safety-concern-escalates"
           :operation-id (get operation :testadmn.proposal/id)})
      :else
      (do (store/log-proposal! store
            (merge operation {:testadmn.proposal/effect :hold}))
          {:status :held-for-approval
           :operation-id (get operation :testadmn.proposal/id)}))))
