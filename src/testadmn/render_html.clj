(ns testadmn.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for `cloud-itonami-isic-855`: this repo
  previously had NO demo page and no generator at all. This namespace drives
  the REAL actor stack (`testadmn.operation/execute-operation` ->
  `testadmn.governor/evaluate-proposal` -> `testadmn.store`) over the named
  seed in `testadmn.store` (`demo-registered-sessions` /
  `demo-unregistered-sessions` / `demo-absent-session-id`) and renders the
  result. Every session id, facility id, proctor id, op type, phase number,
  gate cell and hold reason on the page is read back out of the running
  store or computed by the real governor/phase code -- nothing is hand-typed
  into the HTML (ADR-2607122300 section 1).

  Two things measured in THIS repo shape the design, and both are disclosed
  on the page rather than hidden:

  1. This repo does NOT wire langgraph. `deps.edn` declares it and the
     README calls the modules \"langgraph-clj StateGraph\", but `grep -rn
     langgraph src/` matches nothing -- `operation/execute-operation` is a
     plain function. So the demo drives that function directly instead of
     `g/run*`. Using `g/run*` here would have meant inventing a graph this
     repo does not have.

  2. Governor HARD holds are NOT retained by the store. In
     `operation/execute-operation`, the `(not (:accepted? gov-result))`
     branch returns `{:status :rejected ...}` WITHOUT calling
     `store/log-proposal!`. `governor/reject-proposal` exists and would log
     a `:rollback` record carrying `:_rejected-reason`, but nothing calls
     it. A reader of the proposal log alone therefore cannot tell \"no
     proposal was ever blocked\" from \"blocks happened and were dropped\".
     Rather than hardcode \"this is broken\", `retention` below DERIVES the
     answer per hold by looking the proposal id up in the live proposal log,
     so this page self-corrects the moment `execute-operation` starts
     logging its rejections.

  Determinism: `operation/make-operation` mints ids from `System/nanoTime`,
  so raw proposal ids are NOT rendered -- rows are keyed by a stable
  per-run sequence number instead. The page contains no timestamps. Two
  consecutive runs are byte-identical; verify by diffing two runs written
  into separate scratch directories.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [testadmn.store :as store]
            [testadmn.operation :as op]
            [testadmn.phase :as phase]))

;; ----------------------------- scenario -----------------------------

(def ^:private scenario
  "The ops this demo drives, in render order. Each entry is
  `{:phase :op :session :data}` and optionally `:force-effect`, which
  overwrites `:testadmn.proposal/effect` AFTER `make-operation` built the
  proposal -- the only way to reach `governor/hard-check-2` through the
  real pipeline, since `make-operation` always stamps `:propose` (the test
  suite's `test-hard-check-2-effect-must-be-propose` builds its proposal
  the same way).

  Session ids come from `store/demo-registered-sessions` (session-001,
  session-002), `store/demo-unregistered-sessions` (session-003) and
  `store/demo-absent-session-id` (session-009). Every room label and
  proctor id shown is read from `store/demo-rooms` / `store/demo-proctor-ids`
  rather than typed here, so no identifier on the rendered page lacks a
  named source in this repo. The remaining payload values are proposal
  QUANTITIES (supply counts, attendance counts) using the README's own
  vocabulary for `:coordinate-supply-request` and `:log-attendance-note`;
  they are inputs the demo proposes, not store ground truth."
  [;; --- phase ladder, clean proposals against a registered session ---
   {:phase 0 :op :schedule-test-session :session "session-001"
    :data (store/demo-rooms "session-001")}
   {:phase 1 :op :schedule-test-session :session "session-001"
    :data (store/demo-rooms "session-001")}
   {:phase 2 :op :coordinate-proctor-assignment-proposal :session "session-001"
    :data {:proctor-ids store/demo-proctor-ids}}
   {:phase 3 :op :schedule-test-session :session "session-001"
    :data (store/demo-rooms "session-001")}
   {:phase 3 :op :coordinate-supply-request :session "session-001"
    :data {:answer-sheets 250 :pencils 300}}
   {:phase 3 :op :log-attendance-note :session "session-001"
    :data {:checked-in 218 :absent 6}}
   {:phase 3 :op :flag-safety-concern :session "session-001"
    :data {:concern "proctor observed possible integrity issue at station 5" :safety-concerns [:integrity-incident]}}
   {:phase 3 :op :schedule-test-session :session "session-002"
    :data (store/demo-rooms "session-002")}

   ;; --- HARD check 3: scope exclusion ---
   {:phase 3 :op :schedule-test-session :session "session-002"
    :data {:test-content "swap in revised section 3 items"}}
   {:phase 3 :op :coordinate-supply-request :session "session-002"
    :data {:answer-key "requested for post-session review"}}
   {:phase 3 :op :log-attendance-note :session "session-002"
    :data {:eligibility "confirm candidate is eligible for accommodation"}}

   ;; --- HARD check 2: effect must be :propose ---
   {:phase 3 :op :schedule-test-session :session "session-002"
    :data (store/demo-rooms "session-002") :force-effect :commit}

   ;; --- HARD check 1: session ground truth ---
   {:phase 3 :op :schedule-test-session :session "session-003"
    :data (store/demo-rooms "session-003")}
   {:phase 3 :op :schedule-test-session :session "session-009"
    :data (store/demo-rooms "session-009")}])

(defn- exec!
  "Builds a real operation and runs it through the real pipeline."
  [s {:keys [phase op session data force-effect]}]
  (let [operation (cond-> (op/make-operation op session data)
                    force-effect (assoc :testadmn.proposal/effect force-effect))]
    {:proposal-id (:testadmn.proposal/id operation)
     :phase phase :op op :session session :data data
     :force-effect force-effect
     :result (op/execute-operation operation s phase)}))

(defn run-demo!
  "Seeds a fresh store and drives `scenario` through the real actor.

  Returns `{:store s :timeline [...]}`. Nothing here is simulated: each
  timeline entry's `:result` is verbatim what `operation/execute-operation`
  returned, and `:store` is the live store those calls mutated."
  []
  (let [s (store/seed-demo-store)]
    {:store s
     :timeline (vec (map-indexed (fn [i step] (assoc (exec! s step) :seq (inc i)))
                                 scenario))}))

;; ------------------------- run classification -------------------------

(defn- governor-hold?
  "True when this entry was stopped by the GOVERNOR, as opposed to the phase
  gate. Discriminated structurally, not by string matching: only
  `execute-operation`'s `(not (:accepted? gov-result))` branch attaches
  `:checks` to its return value; the phase-gate branch attaches `:phase`
  and `:operation-type` instead."
  [{:keys [result]}]
  (and (= :rejected (:status result))
       (contains? result :checks)))

(defn- failed-check
  "The first HARD check that actually failed, straight out of the governor's
  own `:checks` vector."
  [{:keys [result]}]
  (some #(when-not (:pass? %) %) (:checks result)))

(defn- retention
  "DERIVED disclosure, not a hardcoded claim: does the store's proposal log
  actually retain a record for this proposal id? Returns the retained record
  or nil. If `execute-operation` is later fixed to call
  `governor/reject-proposal`, this starts returning a record and the page's
  wording changes on its own."
  [s {:keys [proposal-id]}]
  (first (filter #(= proposal-id (:testadmn.proposal/id %)) (store/proposal-log s))))

(defn- holds [{:keys [timeline]}] (filterv governor-hold? timeline))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kv-cell
  "Renders a proposal-data map deterministically (keys sorted by name)."
  [m]
  (->> (sort-by (comp str key) m)
       (map (fn [[k v]] (str (esc k) " " (esc (pr-str v)))))
       (str/join "<br>")))

(defn- status-cell [{:keys [result] :as entry}]
  (let [{:keys [status reason]} result]
    (case status
      :auto-committed "<span class=\"ok\">auto-committed</span>"
      :escalated "<span class=\"warn\">escalated to human</span>"
      :held-for-approval "<span class=\"warn\">held for approval</span>"
      :rejected (if (governor-hold? entry)
                  (str "<span class=\"critical\">HARD hold &middot; " (esc reason) "</span>")
                  (str "<span class=\"muted\">phase gate &middot; " (esc reason) "</span>"))
      (str "<span class=\"muted\">" (esc status) "</span>"))))

;; --- section 1: seeded sessions, read back out of the live store ---

(defn- session-row [s session-id]
  (let [{:testadmn.test-session/keys [name registered? verified?
                                      scheduled-start facility-id]}
        (store/lookup-session s session-id)
        present? (some? name)]
    (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
            (esc session-id)
            (if present? (esc name) "<span class=\"critical\">no record in store</span>")
            (if present? (esc scheduled-start) "&mdash;")
            (if present? (esc facility-id) "&mdash;")
            (cond (not present?) "<span class=\"critical\">absent</span>"
                  registered? "<span class=\"ok\">registered</span>"
                  :else "<span class=\"critical\">not registered</span>")
            (cond (not present?) "&mdash;"
                  verified? "<span class=\"ok\">verified</span>"
                  :else "<span class=\"warn\">unverified</span>"))))

;; --- section 2: phase x op gate matrix, computed from testadmn.phase ---

(def ^:private all-ops
  "Sorted for stable render order; the set itself is the actor's own closed
  allowlist, read from `operation/allowed-ops` rather than restated."
  (vec (sort-by name op/allowed-ops)))

(defn- gate-cell [phase-num op-type]
  (cond
    (not (phase/is-allowed? phase-num op-type))
    "<span class=\"muted\">blocked</span>"
    (phase/should-auto-commit? phase-num op-type)
    "<span class=\"ok\">auto-commit</span>"
    (= op-type :flag-safety-concern)
    "<span class=\"warn\">escalates</span>"
    :else
    "<span class=\"warn\">approval</span>"))

(defn- gate-row [op-type]
  (format "        <tr><td><code>%s</code></td>%s</tr>"
          (esc op-type)
          (str/join (map #(str "<td>" (gate-cell % op-type) "</td>") [0 1 2 3]))))

;; --- section 3: HARD holds, with derived retention disclosure ---

(defn- hold-row [s {:keys [seq op session data force-effect] :as entry}]
  (let [{:keys [reason]} (failed-check entry)
        retained (retention s entry)]
    (format "        <tr><td>%s</td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td>%s</td></tr>"
            seq
            (esc op)
            (esc session)
            (if force-effect
              (str "effect forced to <code>" (esc force-effect) "</code>")
              (kv-cell data))
            (esc reason)
            (if retained
              (str "<span class=\"ok\">retained as <code>"
                   (esc (:testadmn.proposal/effect retained)) "</code></span>")
              "<span class=\"critical\">not retained</span> <span class=\"muted\">(run result only &mdash; the store kept no record)</span>"))))

;; --- section 4: the full run timeline ---

(defn- timeline-row [s {:keys [seq phase op session data force-effect] :as entry}]
  (format "        <tr><td>%s</td><td>%s</td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td></tr>"
          seq phase (esc op) (esc session)
          (if force-effect
            (str "effect forced to <code>" (esc force-effect) "</code>")
            (kv-cell data))
          (status-cell entry)
          (if (retention s entry)
            "<span class=\"ok\">yes</span>"
            "<span class=\"muted\">no</span>")))

;; --- section 5: what the store actually retained ---

(defn- ledger-row [i {:testadmn.proposal/keys [effect type target-session-id proposal-data]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td></tr>"
          (inc i) (esc effect) (esc type) (esc target-session-id)
          (kv-cell proposal-data)))

(defn render
  "Renders the console from a completed `run-demo!` result."
  [{:keys [store timeline] :as run}]
  (let [s store
        hs (holds run)
        ledger (vec (store/proposal-log s))
        dropped (count (remove #(retention s %) hs))
        ;; DERIVED: does any session pass HARD check 1 while still unverified?
        ;; hard-check-1's docstring says "registered? AND verified?" but its
        ;; code only tests :registered?. Computed, so it disappears if fixed.
        unverified-passers
        (->> (concat store/demo-registered-sessions store/demo-unregistered-sessions)
             (map :testadmn.test-session/id)
             (map #(store/lookup-session s %))
             (filter #(and (:testadmn.test-session/registered? %)
                           (not (:testadmn.test-session/verified? %))))
             count)]
    (str
     "<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-855 &middot; test administration</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Educational support activities (ISIC 855) &mdash; Test Administration Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample &middot; governor-gated &middot; test content, grading, eligibility and safety-authority overrides are permanently out of scope</span>\n"
     "</header>\n"
     "<main>\n"

     ;; 1 -- sessions
     "  <section class=\"card\">\n"
     "    <h2>Seeded test sessions</h2>\n"
     "    <p class=\"muted\">Read back out of the live store after seeding, via <code>testadmn.store/lookup-session</code>. Generated at build time by <code>testadmn.render-html</code> (<code>clojure -M:dev:render-html</code>) &mdash; no hand-written rows. <code>session-003</code> is seeded through <code>create-session!</code> and deliberately left unregistered; <code>session-009</code> is deliberately never seeded at all. Both exist so the governor&rsquo;s first HARD check is exercised against real ground truth.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Session</th><th>Name</th><th>Scheduled start</th><th>Facility</th><th>Registration</th><th>Verification</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial session-row s) (store/demo-session-ids))) "\n"
     "      </tbody>\n"
     "    </table>\n"
     (when (pos? unverified-passers)
       (str "    <p class=\"muted\"><strong>Derived note:</strong> " unverified-passers
            " of the seeded sessions are <code>registered?</code> but still <code>verified?&nbsp;false</code>, and the governor admits them anyway. <code>hard-check-1</code>&rsquo;s docstring reads &ldquo;must exist AND be :registered?/:verified?&rdquo;, but its code tests only <code>:registered?</code> &mdash; and <code>register-session!</code> unconditionally writes <code>:verified? false</code>, so no session can currently reach a verified state through the public API. This note is computed from live store state, so it disappears on its own if that gap is closed.</p>\n"))
     "  </section>\n"

     ;; 2 -- gate matrix
     "  <section class=\"card\">\n"
     "    <h2>Phase &times; operation gate</h2>\n"
     "    <p class=\"muted\">Computed by calling <code>testadmn.phase/is-allowed?</code> and <code>should-auto-commit?</code> for every phase and every op in <code>testadmn.operation/allowed-ops</code> &mdash; this table is real output of the phase code, not a description of it. <code>:flag-safety-concern</code> never auto-commits at any phase.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Operation</th><th>Phase 0</th><th>Phase 1</th><th>Phase 2</th><th>Phase 3</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map gate-row all-ops)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     ;; 3 -- hard holds
     "  <section class=\"card\">\n"
     "    <h2>Governor HARD holds (this run)</h2>\n"
     "    <p class=\"muted\">" (count hs)
     " proposals were stopped by the governor and never reached a human. These are un-overridable: the reason shown is taken from the failing entry of the governor&rsquo;s own <code>:checks</code> vector. Rows are identified as governor holds structurally &mdash; only the governor branch of <code>execute-operation</code> attaches <code>:checks</code> &mdash; so a phase-gate block is never miscounted as a HARD hold.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>#</th><th>Operation</th><th>Session</th><th>Proposal payload</th><th>Failed HARD check</th><th>Kept by store?</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial hold-row s) hs)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     (when (pos? dropped)
       (str "    <p class=\"muted\"><strong>Retention gap:</strong> " dropped " of these " (count hs)
            " HARD holds left no record in the store. <code>operation/execute-operation</code> returns <code>{:status :rejected}</code> without calling <code>store/log-proposal!</code>; <code>governor/reject-proposal</code> would log a <code>:rollback</code> record carrying <code>:_rejected-reason</code>, but nothing calls it. The holds above are therefore joined from the live run result, not from the audit ledger &mdash; a reader of the ledger alone could not tell &ldquo;nothing was ever blocked&rdquo; from &ldquo;blocks happened and were dropped&rdquo;. This paragraph is derived per-hold by looking each proposal id up in the ledger, so it corrects itself once those rejections are logged.</p>\n"))
     "  </section>\n"

     ;; 4 -- timeline
     "  <section class=\"card\">\n"
     "    <h2>Operation timeline (this run)</h2>\n"
     "    <p class=\"muted\">Every op the scenario drove, in order, with the verbatim status returned by <code>testadmn.operation/execute-operation</code>. Proposal ids are minted from <code>System/nanoTime</code> and so are deliberately not shown; rows are keyed by a stable sequence number instead, which is what keeps this page byte-identical across runs.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>#</th><th>Phase</th><th>Operation</th><th>Session</th><th>Proposal payload</th><th>Outcome</th><th>In ledger?</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial timeline-row s) timeline)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     ;; 5 -- ledger
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger retained by the store</h2>\n"
     "    <p class=\"muted\">The append-only proposal log as <code>testadmn.store/proposal-log</code> actually returns it after the run &mdash; " (count ledger)
     " records out of " (count timeline) " operations driven. The difference is the phase-gate blocks and the HARD holds, neither of which is currently written to the log.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>#</th><th>Effect</th><th>Operation</th><th>Session</th><th>Proposal payload</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map-indexed ledger-row ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "<footer>\n"
     "  <p>Generated from a live run of <code>testadmn.operation</code> &rarr; <code>testadmn.governor</code> &rarr; <code>testadmn.store</code> over the seed in <code>testadmn.store/demo-registered-sessions</code>. Regenerate with <code>clojure -M:dev:render-html</code>.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        run (run-demo!)
        hs (holds run)]
    ;; Build-time invariant, not a convention: a console that shows no HARD
    ;; hold is not evidence that this actor is contained. Fail the build.
    (when (zero? (count hs))
      (throw (ex-info "render-html: scenario produced 0 governor HARD holds -- refusing to write a console that cannot demonstrate containment"
                      {:ops (count (:timeline run))
                       :holds 0})))
    (let [html (render run)
          dropped (count (remove #(retention (:store run) %) hs))]
      (spit out html)
      (println "wrote" out
               (str "(" (count (:timeline run)) " ops, "
                    (count hs) " HARD holds across "
                    (count (distinct (map (comp :reason failed-check) hs))) " distinct rules, "
                    (count (store/proposal-log (:store run))) " ledger records, "
                    dropped " holds not retained by the store)")))))
