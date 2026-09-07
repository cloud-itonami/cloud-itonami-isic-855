;; testadmn.sim — Test Administration Simulation

(ns testadmn.sim
  (:require [testadmn.store :as store]
            [testadmn.advisor :as advisor]
            [testadmn.governor :as gov]
            [testadmn.operation :as op]
            [testadmn.phase :as phase]))

(comment
  "Simulation harness for testing the complete actor flow.")

(defn simulate-session
  "Simulate a test administration session."
  []
  (let [s (store/new-mem-store)
        adv (advisor/new-mock-advisor)]
    ;; Register a test session
    (store/register-session! s "session-001"
      {:testadmn.test-session/name "SAT Administration 2026-07-15"
       :testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
       :testadmn.test-session/facility-id "facility-101"})

    ;; Generate and execute operations at different phases
    (let [phase-0-result
          (let [operation (op/make-operation :schedule-test-session "session-001"
                                              {:room "Gym A" :proctors 3})]
            (op/execute-operation operation s 0))

          phase-1-result
          (let [operation (op/make-operation :schedule-test-session "session-001"
                                              {:room "Gym A" :proctors 3})]
            (op/execute-operation operation s 1))

          phase-2-result
          (let [operation (op/make-operation :coordinate-proctor-assignment-proposal "session-001"
                                              {:proctor-ids ["p1" "p2" "p3"]})]
            (op/execute-operation operation s 2))

          ;; Test scope exclusion - should be rejected
          rejected-result
          (let [operation (op/make-operation :schedule-test-session "session-001"
                                              {:test-content "forbidden" :answer-key "no"})]
            (op/execute-operation operation s 2))

          ;; Test safety concern - should escalate
          safety-result
          (let [operation (op/make-operation :flag-safety-concern "session-001"
                                              {:concern "proctor observed possible integrity issue at station 5" :safety-concerns [:integrity-incident] :facility-id "station-5"})]
            (op/execute-operation operation s 2))]

      {:phase-0 phase-0-result
       :phase-1 phase-1-result
       :phase-2 phase-2-result
       :rejected rejected-result
       :safety safety-result
       :proposal-log (store/proposal-log s)})))

(defn run-demo
  "Run demonstration and return results."
  []
  (simulate-session))
