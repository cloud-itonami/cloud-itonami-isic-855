# cloud-itonami-isic-855

Educational support activities (ISIC 855) actor — Test administration logistics coordination.

**Focus domain**: Educational testing/exam-administration logistics coordination (scheduling test sessions, proctor assignments, supply coordination, attendance logging, safety-concern flagging, accessibility accommodation logistics).

**Out of scope**: Test content decisions, grading/scoring, eligibility determinations, academic policy, safety-authority overrides.

## Architecture

Actor modules (all `.cljc`, langgraph-clj StateGraph):
- `testadmn.store` — Test session registry and proposal audit ledger
- `testadmn.advisor` — LLM advisor interface (mock in this version)
- `testadmn.governor` — Eight HARD, permanent, un-overridable checks
- `testadmn.phase` — Staged rollout (Phase 0→3)
- `testadmn.operation` — Closed :propose-only op allowlist
- `testadmn.sim` — Simulation and demo

## Governor: Eight HARD Checks

1. **Test-session verified** — target must exist AND be `:registered?`/`:verified?` in store
2. **Effect is :propose** — any other :effect rejected outright
3. **Scope exclusion** — test-content, answer-keys, grading, eligibility, academic-integrity adjudication, or safety-authority overrides are blocked. Legitimate `:flag-safety-concern` escalates only, never auto-commits.
4. **Proctor impartiality** — a `:coordinate-proctor-assignment-proposal` must not name a proctor declared non-impartial (teaching/related to a registered test-taker of the session). A conflicted proctor is rejected outright, never held and never auto-committed at Phase 3.
5. **Accessibility accommodation is logistics-only** — a `:coordinate-accommodation-logistics` proposal must declare at least one recognized accommodation category (`:time-extension`, `:reader/scribe`, `:accessible-room`, `:alternate-format`, `:assistive-tech`) and must not carry any test-content, grading, eligibility, or policy change. An accommodation arranges HOW a test-taker accesses a session, never WHAT is judged. A category-less, unknown-category, or content-bearing accommodation is rejected outright, never auto-committed at Phase 3.
6. **Test-taker enrollment binding** — attendance and accommodations may name only people actually registered to sit THAT session. Each session carries a `:testadmn.test-session/roster` (the set of enrolled test-taker ids). A `:log-attendance-note` (every id in `:check-in`/`:absent`) and a `:coordinate-accommodation-logistics` (its `:test-taker`) must each be a member of the target session's roster. A roster-less session, or any proposal naming an un-enrolled/unknown id, is rejected outright — never held, never auto-committed at Phase 3 (anti-impersonation / anti-proxy-testing: you cannot log check-in for, or arrange access for, a fabricated person).
7. **Bounded supply-consumable allowlist** — a `:coordinate-supply-request` must name at least one recognized non-content consumable (answer sheets, pencils, scratch paper, erasers, timers, etc.) and must not name any unrecognized item. Scope-exclusion (check 3) only blocks content-bearing terms, so without this check an arbitrary unsanctioned consumable would pass and auto-commit at Phase 3. A missing-item or unrecognized-item supply request is rejected outright, never held and never auto-committed at Phase 3.
8. **Attendance self-contradiction** — a `:log-attendance-note` must NOT mark the same test-taker as both `:check-in` and `:absent` in the same session; the two sets must be disjoint. Marking the same id in both sets is an ambiguous attendance record (the paper-trail equivalent of proxy/ghost attendance) and is rejected outright — never held, never auto-committed at Phase 3.

## Closed :propose-only Allowlist

- `:schedule-test-session` — exam session scheduling/room logistics
- `:coordinate-proctor-assignment-proposal` — administrative proctor PROPOSAL only
- `:coordinate-supply-request` — non-content consumables (answer sheets, pencils)
- `:log-attendance-note` — test-session attendance/check-in logging
- `:flag-safety-concern` — facility/integrity/wellbeing concerns (always escalates)
- `:coordinate-accommodation-logistics` — accessibility/reasonable accommodations (extra time, reader/scribe, accessible room, alternate format, assistive tech)

Proctor assignment proposal shape (impartiality is MANDATORY on every proctor):

```clojure
{:proctors [{:proctor/id "A. Yamada" :testadmn.proposal/proctor-impartial? true}
            {:proctor/id "B. Sato"   :testadmn.proposal/proctor-impartial? true}]}
```

A proctor with `:testadmn.proposal/proctor-impartial? false` (or any proctor
that is not explicitly declared impartial) is `proctor-conflict-of-interest`
rejected by the governor — never held at Phase 2, never auto-committed at
Phase 3.

Accommodation proposal shape (`:accommodations` carries category keywords from
the closed set; the governor rejects unknown or omitted categories and any
content-bearing payload):

```clojure
{:accommodations [:time-extension :alternate-format]}
```

## Staged Phases

- **Phase 0**: read-only
- **Phase 1**: `:schedule-test-session` (approval-gated)
- **Phase 2**: + `:coordinate-proctor-assignment-proposal`, `:coordinate-supply-request`, `:coordinate-accommodation-logistics` (approval-gated)
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