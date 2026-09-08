;; testadmn_render_test — coverage for the two untested ISIC-855 public
;; surfaces: the proposal advisor (testadmn.advisor) and the operator
;; console renderer (testadmn.render-html).
;;
;; Every HARD check, op, phase, sim, and store contract is exercised in
;; testadmn_test.clj, but the advisor that GENUINATES proposals and the
;; console that OPERATORS READ are driven only as transitive callers —
;; never asserted directly. This file closes that gap so the full actor
;; stack (advisor -> operation -> governor -> store -> console) is covered.

(ns testadmn-render-test
  (:require [kotoba.lang.text :as str]
            [clojure.test :refer :all]
            [testadmn.advisor :as advisor]
            [testadmn.store :as store]
            [testadmn.render-html :as render]))

;; === Advisor contract (testadmn.advisor) ===
;; The advisor is the proposal-genuination front door. The mock must hand
;; the governor a well-formed :propose proposal for the requested session —
;; if it ever started emitting :commit (or dropped the session id), the
;; governor's HARD checks could be silently bypassed at the source.

(deftest test-mock-advisor-returns-propose-schedule
  (let [a (advisor/new-mock-advisor)
        ctx {:session-id "session-001" :room "Gym A"}
        p (advisor/propose-operation a ctx)]
    (is (= :propose (:testadmn.proposal/effect p)))
    (is (= :schedule-test-session (:testadmn.proposal/type p)))
    (is (= "session-001" (:testadmn.proposal/target-session-id p)))
    (is (= ctx (:testadmn.proposal/proposal-data p)))
    (is (string? (:testadmn.proposal/id p)))))

(deftest test-mock-advisor-unknown-session-defaults
  ;; The advisor does not invent a verifier: with no session id in context it
  ;; falls back to "unknown" so the governor's HARD check 1 still rejects the
  ;; proposal against a non-existent record (never a fabricated one).
  (let [a (advisor/new-mock-advisor)
        p (advisor/propose-operation a {})]
    (is (= "unknown" (:testadmn.proposal/target-session-id p)))))

;; === Operator console (testadmn.render-html) ===
;; The console is this actor's operator-facing deliverable (README flagship
;; item 2). Its correctness contract is that every row is DERIVED from a live
;; run of the real actor stack, never hand-typed — so the tests assert the
;; run it renders from actually exercised the whole pipeline and that the
;; rendered output is byte-identical across runs (the determinism the
;; namespace promises).

(deftest test-render-run-demo-drives-whole-stack
  (let [run (render/run-demo!)]
    (is (contains? run :store))
    (is (contains? run :timeline))
    ;; timeline is ordered by sequence and non-empty
    (is (seq (:timeline run)))
    ;; every entry carried a result through operation/execute-operation
    (is (every? #(contains? (:result %) :status) (:timeline run)))
    ;; governor HARD holds still occur (the console must not paper over them)
    (is (some (fn [{:keys [result]}]
                (and (= :rejected (:status result))
                     (contains? result :checks)))
              (:timeline run)))))

(deftest test-render-determinism
  ;; Two consecutively rendered consoles must be byte-identical. The console
  ;; keys rows by a stable seq number, not the nanoTime-minted proposal ids,
  ;; which is what makes this possible (namespace docstring promises two
  ;; consecutive runs are byte-identical).
  (let [render-html-str #(render/render (render/run-demo!))]
    (is (string? (render-html-str)))
    (is (= (render-html-str) (render-html-str)))))

(deftest test-render-reflects-live-store-sessions
  ;; The sessions section reads session ids straight out of the store's seed;
  ;; every demo session id (including the deliberately-absent one) appears in
  ;; the rendered console.
  (let [html (render/render (render/run-demo!))]
    (doseq [sid (store/demo-session-ids)]
      (is (true? (str/includes? html sid))))))