# cloud-itonami-isic-855

Educational support activities (ISIC 855) actor — Test administration logistics coordination.

**Focus domain**: Educational testing/exam-administration logistics coordination (scheduling test sessions, proctor assignments, supply coordination, attendance logging, safety-concern flagging).

**Out of scope**: Test content decisions, grading/scoring, eligibility determinations, academic policy, safety-authority overrides.

## Architecture

Actor modules (all `.cljc`, langgraph-clj StateGraph):
- `testadmn.store` — Test session registry and proposal audit ledger
- `testadmn.advisor` — LLM advisor interface (mock in this version)
- `testadmn.governor` — Four HARD, permanent, un-overridable checks
- `testadmn.phase` — Staged rollout (Phase 0→3)
- `testadmn.operation` — Closed :propose-only op allowlist
- `testadmn.sim` — Simulation and demo

## Governor: Four HARD Checks

1. **Test-session verified** — target must exist AND be `:registered?`/`:verified?` in store
2. **Effect is :propose** — any other :effect rejected outright
3. **Scope exclusion** — test-content, answer-keys, grading, eligibility, academic-integrity adjudication, or safety-authority overrides are blocked. Legitimate `:flag-safety-concern` escalates only, never auto-commits.
4. **Proctor impartiality** — a `:coordinate-proctor-assignment-proposal` must not name a proctor declared non-impartial (teaching/related to a registered test-taker of the session). A conflicted proctor is rejected outright, never held and never auto-committed at Phase 3.

## Closed :propose-only Allowlist

- `:schedule-test-session` — exam session scheduling/room logistics
- `:coordinate-proctor-assignment-proposal` — administrative proctor PROPOSAL only
- `:coordinate-supply-request` — non-content consumables (answer sheets, pencils)
- `:log-attendance-note` — test-session attendance/check-in logging
- `:flag-safety-concern` — facility/integrity/wellbeing concerns (always escalates)

Proctor assignment proposal shape (impartiality is MANDATORY on every proctor):

```clojure
{:proctors [{:proctor/id "A. Yamada" :testadmn.proposal/proctor-impartial? true}
            {:proctor/id "B. Sato"   :testadmn.proposal/proctor-impartial? true}]}
```

A proctor with `:testadmn.proposal/proctor-impartial? false` (or any proctor
that is not explicitly declared impartial) is `proctor-conflict-of-interest`
rejected by the governor — never held at Phase 2, never auto-committed at
Phase 3.

## Staged Phases

- **Phase 0**: read-only
- **Phase 1**: `:schedule-test-session` (approval-gated)
- **Phase 2**: + `:coordinate-proctor-assignment-proposal`, `:coordinate-supply-request` (approval-gated)
- **Phase 3**: auto-commits clean proposals; safety concerns always escalate

## Testing

```bash
clojure -M:dev:test
```

## Lint

```bash
clojure -M:lint
```

## Demo

```bash
clojure -M:dev -e "(require 'testadmn.sim) (clojure.pprint/pprint (testadmn.sim/run-demo))"
```

## Governance

- License: AGPL-3.0-or-later
- Audit ledger: append-only, immutable
- Registry: `kotoba-lang/industry` entry 855

## References

- ADR-2607121000: cloud-itonami global ISIC/ISCO Wave structure
- ADR-2607152700: ISIC-873 eldercare coordination
- ADR-2607153700: ISIC-852 secondary education
- ADR-2607152900: ISIC-851 primary education