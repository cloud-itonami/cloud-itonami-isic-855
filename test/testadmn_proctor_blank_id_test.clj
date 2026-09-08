;; testadmn_proctor_blank_id_test — HARD CHECK 24: proctor id must be non-blank.
;;
;; ISIC-855 test administration assigns proctors to supervise a session through
;; the :coordinate-proctor-assignment-proposal op. The anti-inflation checks
;; close the empty-list (HARD CHECK 10), the duplicate (HARD CHECK 13) and the
;; enrolled-conflict (HARD CHECK 21) holes — but none rejects a proctor entry
;; whose :proctor/id is BLANK (nil, "", or whitespace-only). A map-form proctor
;; {:proctor/id "" :testadmn.proposal/proctor-impartial? true} passes check 4
;; (it is DECLARED impartial, and a string-form blank's implicit-conflict
;; default never fires on an explicit map), check 10 (list non-empty), check 13
;; (one blank is not a duplicate), and check 21 ("" is not on the roster), then
;; auto-commits at Phase 3 a supervisor with NO identity — the same
;; fabricated-body inflation a duplicated id closes, achieved via blindness
;; instead of repetition. HARD CHECK 24 closes it: rejected outright, never
;; held, never auto-committed at Phase 3.

(ns testadmn-proctor-blank-id-test
  (:require [clojure.test :refer [deftest is]]
            [testadmn.store :as store]
            [testadmn.governor :as gov]
            [testadmn.operation :as op]))

(defn- pa-proposal [proposal-data]
  {:testadmn.proposal/id "pa-24"
   :testadmn.proposal/type :coordinate-proctor-assignment-proposal
   :testadmn.proposal/effect :propose
   :testadmn.proposal/target-session-id "sess-001"
   :testadmn.proposal/proposal-data proposal-data})

(defn- check24-result [result]
  (->> (:checks result)
       (filter (fn [c] (contains? #{"proctor-id-non-blank"
                                    "proctor-id-blank"
                                    "not-a-proctor-assignment"}
                                  (:reason c))))
       first))

(defn- session [s roster]
  (store/register-session! s "sess-001"
    {:testadmn.test-session/scheduled-start "2026-07-15T09:00:00Z"
     :testadmn.test-session/facility-id "facility-101"
     :testadmn.test-session/roster roster}))

(deftest test-non-blank-proctor-ids-pass
  ;; Distinct, non-blank, explicitly-impartial proctor ids are accepted
  ;; (checks 4, 10, 13, 21, 24 all pass).
  (let [s (store/new-mem-store)]
    (session s #{"s001" "s002"})
    (let [result (gov/evaluate-proposal s
                  (pa-proposal {:proctors [{:proctor/id "A. Smith"
                                            :testadmn.proposal/proctor-impartial? true}
                                           {:proctor/id "B. Sato"
                                            :testadmn.proposal/proctor-impartial? true}]}))]
      (is (true? (:accepted? result)))
      (is (= "all-checks-pass" (:reason result)))
      (is (= "proctor-id-non-blank" (:reason (check24-result result)))))))

(deftest test-empty-string-proctor-id-rejected
  ;; {:proctor/id "" :testadmn.proposal/proctor-impartial? true} is the exact
  ;; vector that slipped past checks 4/10/13/21: a nameless supervisor whose
  ;; declared impartiality keeps check 4 from defaulting it to conflicted.
  (let [s (store/new-mem-store)]
    (session s #{"s001" "s002"})
    (let [result (gov/evaluate-proposal s
                  (pa-proposal {:proctors [{:proctor/id ""
                                            :testadmn.proposal/proctor-impartial? true}]}))]
      (is (false? (:accepted? result)))
      (is (= "proctor-id-blank" (:reason result))))))

(deftest test-whitespace-proctor-id-rejected
  ;; A whitespace-only id is equally blank.
  (let [s (store/new-mem-store)]
    (session s #{"s001" "s002"})
    (let [result (gov/evaluate-proposal s
                  (pa-proposal {:proctors [{:proctor/id "   "
                                            :testadmn.proposal/proctor-impartial? true}]}))]
      (is (false? (:accepted? result)))
      (is (= "proctor-id-blank" (:reason result))))))

(deftest test-nil-proctor-id-rejected
  ;; A proctor map carrying no :proctor/id (nil) is a nameless supervisor too.
  (let [s (store/new-mem-store)]
    (session s #{"s001" "s002"})
    (let [result (gov/evaluate-proposal s
                  (pa-proposal {:proctors [{:testadmn.proposal/proctor-impartial? true}]}))]
      (is (false? (:accepted? result)))
      (is (= "proctor-id-blank" (:reason result))))))

(deftest test-non-proctor-op-unaffected
  ;; The check only guards :coordinate-proctor-assignment-proposal; other ops
  ;; pass check 24 trivially.
  (let [s (store/new-mem-store)]
    (session s #{"s001" "s002"})
    (let [result (gov/evaluate-proposal s
                  {:testadmn.proposal/id "p1"
                   :testadmn.proposal/type :schedule-test-session
                   :testadmn.proposal/effect :propose
                   :testadmn.proposal/target-session-id "sess-001"
                   :testadmn.proposal/proposal-data {:room "Gym A" :proctors 3}})]
      (is (true? (:accepted? result)))
      (is (= "not-a-proctor-assignment" (:reason (check24-result result)))))))

(deftest test-blank-proctor-id-never-auto-commits-phase3
  ;; End to end: at Phase 3 a blank proctor id is rejected by the governor and
  ;; never auto-committed.
  (let [s (store/new-mem-store)]
    (session s #{"s001" "s002"})
    (let [operation (op/make-operation :coordinate-proctor-assignment-proposal "sess-001"
                                        {:proctors [{:proctor/id ""
                                                     :testadmn.proposal/proctor-impartial? true}]})
          result (op/execute-operation operation s 3)]
      (is (= :rejected (:status result)))
      (is (= "proctor-id-blank" (:reason result))))))

(deftest test-non-blank-proctor-still-auto-commits-phase3
  ;; A clean named proctor still auto-commits at Phase 3.
  (let [s (store/new-mem-store)]
    (session s #{"s001" "s002"})
    (let [operation (op/make-operation :coordinate-proctor-assignment-proposal "sess-001"
                                        {:proctors [{:proctor/id "A. Smith"
                                                     :testadmn.proposal/proctor-impartial? true}]})
          result (op/execute-operation operation s 3)]
      (is (= :auto-committed (:status result))))))

(deftest test-check8-still-last-after-check24
  ;; HARD CHECK 8 (attendance self-contradiction) stays the LAST entry of the
  ;; governor's :checks vector, so testadmn_integrity_test's (last :checks)
  ;; contract keeps holding after HARD CHECK 24 is added.
  (let [s (store/new-mem-store)]
    (store/register-session! s "sess-001"
      {:testadmn.test-session/roster #{"s001" "s002"}})
    (let [result (gov/evaluate-proposal s
                  {:testadmn.proposal/id "p1"
                   :testadmn.proposal/target-session-id "sess-001"
                   :testadmn.proposal/effect :propose
                   :testadmn.proposal/type :log-attendance-note
                   :testadmn.proposal/proposal-data
                   {:check-in ["s001"] :absent ["s002"]}})
          check8 (last (:checks result))]
      (is (= "attendance-non-contradictory" (:reason check8))))))