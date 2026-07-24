;; testadmn.advisor — Test Administration Advisor (LLM interface)

(ns testadmn.advisor)

#?(:clj (defn- nano-time [] (System/nanoTime)))
#?(:cljs (defn- nano-time [] (* 1e6 (js/performance.now))))

(comment
  "Advisor generates proposals for test administration operations.
   In production, this would integrate with langchain-clj LLM.
   For now, provides a mock interface.")

(defprotocol TestAdmnAdvisor
  "Advisor interface for generating proposals."
  (propose-operation [advisor context]))

(deftype MockAdvisor []
  TestAdmnAdvisor
  (propose-operation [_this context]
    ;; Mock: return a proposal based on context
    {:testadmn.proposal/id (str "prop-" (nano-time))
     :testadmn.proposal/type :schedule-test-session
     :testadmn.proposal/effect :propose
     :testadmn.proposal/target-session-id (get context :session-id "unknown")
     :testadmn.proposal/proposal-data context}))

(defn new-mock-advisor []
  (->MockAdvisor))
