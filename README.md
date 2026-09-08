# cloud-itonami-isic-855

Educational support activities (ISIC 855) actor — Test administration logistics coordination.

**Focus domain**: Educational testing/exam-administration logistics coordination (scheduling test sessions, proctor assignments, supply coordination, attendance logging, safety-concern flagging, accessibility accommodation logistics).

**Out of scope**: Test content decisions, grading/scoring, eligibility determinations, academic policy, safety-authority overrides.

## Architecture

Actor modules (all `.cljc`, langgraph-clj StateGraph):
- `testadmn.store` — Test session registry and proposal audit ledger
- `testadmn.advisor` — LLM advisor interface (mock in this version)
- `testadmn.governor` — Twenty-six HARD, permanent, un-overridable checks
- `testadmn.phase` — Staged rollout (Phase 0→3)
- `testadmn.operation` — Closed :propose-only op allowlist
- `testadmn.sim` — Simulation and demo

## Governor: Twenty-six HARD Checks

1. **Test-session verified** — target must exist AND be `:registered?`/`:verified?` in store
2. **Effect is :propose** — any other :effect rejected outright
3. **Scope exclusion** — test-content, answer-keys, grading, eligibility, academic-integrity adjudication, or safety-authority overrides are blocked. Legitimate `:flag-safety-concern` escalates only, never auto-commits.
4. **Proctor impartiality** — a `:coordinate-proctor-assignment-proposal` must not name a proctor declared non-impartial (teaching/related to a registered test-taker of the session). A conflicted proctor is rejected outright, never held and never auto-committed at Phase 3.
5. **Accessibility accommodation is logistics-only** — a `:coordinate-accommodation-logistics` proposal must declare at least one recognized accommodation category (`:time-extension`, `:reader/scribe`, `:accessible-room`, `:alternate-format`, `:assistive-tech`) and must not carry any test-content, grading, eligibility, or policy change. An accommodation arranges HOW a test-taker accesses a session, never WHAT is judged. A category-less, unknown-category, or content-bearing accommodation is rejected outright, never auto-committed at Phase 3.
6. **Test-taker enrollment binding** — attendance and accommodations may name only people actually registered to sit THAT session. Each session carries a `:testadmn.test-session/roster` (the set of enrolled test-taker ids). A `:log-attendance-note` (every id in `:check-in`/`:absent`) and a `:coordinate-accommodation-logistics` (its `:test-taker`) must each be a member of the target session's roster. A roster-less session, or any proposal naming an un-enrolled/unknown id, is rejected outright — never held, never auto-committed at Phase 3 (anti-impersonation / anti-proxy-testing: you cannot log check-in for, or arrange access for, a fabricated person).
7. **Bounded supply-consumable allowlist** — a `:coordinate-supply-request` must name at least one recognized non-content consumable (answer sheets, pencils, scratch paper, erasers, timers, etc.) and must not name any unrecognized item. Scope-exclusion (check 3) only blocks content-bearing terms, so without this check an arbitrary unsanctioned consumable would pass and auto-commit at Phase 3. A missing-item or unrecognized-item supply request is rejected outright, never held and never auto-committed at Phase 3.
8. **Attendance self-contradiction** — a `:log-attendance-note` must NOT mark the same test-taker as both `:check-in` and `:absent` in the same session; the two sets must be disjoint. Marking the same id in both sets is an ambiguous attendance record (the paper-trail equivalent of proxy/ghost attendance) and is rejected outright — never held, never auto-committed at Phase 3.

9. **Proctor staffing sufficiency** — a `:schedule-test-session` must declare a
   positive proctor headcount (`:proctors >= 1`) in its proposal-data. An exam
   cannot be administered with zero proctors — nobody to verify attendance,
   supervise test-takers, or distribute/collect supplies. HARD CHECK 4
   (impartiality) only guards the separate `:coordinate-proctor-assignment-proposal`
   op; it says nothing about whether a session is staffed at all. A schedule
   naming zero (or omitting) proctors is rejected outright — never held, never
   auto-committed at Phase 3.
10. **Non-empty proctor assignment** — a `:coordinate-proctor-assignment-proposal`
   must name at least one proctor. HARD CHECK 4 (impartiality) only rejects
   DECLARED-conflicted proctors; a vacuous assignment naming nobody passes
   checks 1–9 and, at Phase 3, would auto-commit “no one is assigned” for a
   session HARD CHECK 9 already required to be staffed (`:proctors >= 1`). An
   empty assignment is rejected outright — never held, never auto-committed.

11. **Safety-concern category** — a `:flag-safety-concern` must declare at least
   one recognized safety-concern category (`:facility-hazard`,
   `:test-taker-wellbeing`, `:integrity-incident`, `:environmental-hazard`). HARD
   CHECK 3 only legitimizes safety flags (the type keyword itself carries the
   flagging keywords), so an empty ``no actual concern'' would pass scope-exclusion
   and escalate as a content-free no-op. This check requires the flag to name
   WHAT, so every escalation is triage-actionable — an empty or
   unknown-category safety flag is rejected outright, never held, never
   auto-committed at Phase 3.

12. **Schedule verified** — the target session of a `:schedule-test-session` must carry a concrete venue (`:testadmn.test-session/facility-id`) and a start time (`:testadmn.test-session/scheduled-start`). `:schedule-test-session` is the Phase-1 scheduling act and the first op the allowlist auto-commits at Phase 3, so an unschedulable session (no room, no time) must be rejected outright rather than auto-committed as if a logistics plan existed. A venue-less or start-less schedule is rejected outright, never held and never auto-committed at Phase 3.

13. **No duplicate proctor assignment** — a
   `:coordinate-proctor-assignment-proposal` must not name the same proctor id
   more than once. HARD CHECK 10 (non-empty) only requires that SOMEONE is
   named, and HARD CHECK 4 (impartiality) only rejects declared-conflicted
   proctors; neither guards against the SAME proctor being doubled in the
   paper trail. Doubling an id fabricates a second proctor in the room — it
   inflates the observable staffing roster without adding an actual body, so a
   malformed assignment could claim more supervision than exists. A
   duplicated proctor id is rejected outright, never held, never
   auto-committed at Phase 3.

14. **No duplicate attendance test-taker** — a `:log-attendance-note` must not
   name the same test-taker id more than once within `:check-in` or within
   `:absent`. HARD CHECK 6 (enrollment binding) collapses both into one set for
   membership and HARD CHECK 8 requires the two to be disjoint, but neither
   guards against a repeated id within one field — which sets silently collapse.
   Repeating an id inflates the nominal attendance count without adding an
   actual seated body (the attendance-side analog of check 13's duplicated
   proctor), so a duplicated attendance id is rejected outright, never held,
   never auto-committed at Phase 3.

15. **No duplicate supply item** — a `:coordinate-supply-request` must not
   name the same consumable more than once. HARD CHECK 7 (bounded consumable
   allowlist) only requires that every named item is a recognized non-content
   consumable; it never guards against the SAME item being listed twice.
   Repeating an item is the supply-side analog of check 13's duplicated
   proctor and check 14's duplicated attendance id: it inflates the nominal
   supply count (and the consumed-stock ledger) without adding an actual
   physical delivery item to the room. A duplicated supply item is rejected
   outright, never held, never auto-committed at Phase 3.

16. **No duplicate accommodation rule** — a
   `:coordinate-accommodation-logistics` must not name the same accommodation
   category more than once. HARD CHECK 5 (logistics-only categories) only
   requires that every named category is one of the recognized closed set; it
   never guards against the SAME category being declared twice. Repeating a
   category is the accommodation-side analog of check 13's duplicated
   proctor, check 14's duplicated attendance id, and check 15's duplicated
   supply item: it inflates the nominal accommodation count (the number of
   access arrangements declared) without adding an actual separate
   arrangement to the room. A duplicated accommodation category is rejected
   outright, never held, never auto-committed at Phase 3.


17. **Safety-concern referent** — a `:flag-safety-concern` must name at least
   one concrete referent so triage knows WHERE or WHO: a non-blank
   `:facility-id` (facility/environmental hazards) and/or a `:test-taker-id`
   (wellbeing / integrity concern about a person). HARD CHECK 11 only
   requires a recognized CATEGORY; it says nothing about whether the flag
   points at any actual room or person. A flag naming a category but no
   referent still escalates (check 3 legitimizes every flag carrying flagging
   keywords) as a content-free no-op that nobody can triage or act on. A
   referent-less safety flag is rejected outright — never held, never
   auto-committed at Phase 3.
18. **Schedule enrolled roster** — the target session of a
    `:schedule-test-session` must carry a non-empty enrolled test-taker
    roster. HARD CHECK 6 (enrollment binding) only constrains
    attendance/accommodation proposals that NAME a test-taker; a
    `:schedule-test-session` names none, so without this check a
    roster-less session passes 1–17 and, at Phase 3, would auto-commit as
    a plan to run an exam nobody is enrolled to sit (the scheduling-side
    analog of ghost/proxy testing). A schedule targeting a session with no
    enrolled roster is rejected outright — never held, never auto-committed
    at Phase 3.

19. **Safety-referent category match** — a `:flag-safety-concern` must carry
   the referent kind its category implies. HARD CHECK 11 only requires a
   recognized CATEGORY and HARD CHECK 17 only requires SOME referent
   (`:facility-id` and/or `:test-taker-id`); neither ties the referent's type
   to the category, so a `:facility-hazard` flagged with only a person id
   (or a `:test-taker-wellbeing` flagged with only a room id) would still
   escalate into the wrong triage lane. This check closes that: a
   facility-class category (`:facility-hazard`, `:environmental-hazard`)
   **must** name a non-blank `:facility-id`, and `:test-taker-wellbeing`
   **must** name a non-blank `:test-taker-id`. A category whose required
   referent kind is absent is rejected outright — never held, never
   auto-committed at Phase 3.
20. **Downstream logistics require a scheduled session** — a
   `:log-attendance-note`, `:coordinate-proctor-assignment-proposal`,
   `:coordinate-supply-request`, or `:coordinate-accommodation-logistics`
   must target a session that already carries both a concrete venue
   (`:testadmn.test-session/facility-id`) and a start time
   (`:testadmn.test-session/scheduled-start`). HARD CHECK 12 (schedule-verified)
   only guards `:schedule-test-session`; these four downstream ops each
   auto-commit at Phase 3, so without this check a session that was never
   scheduled (no room, no time) could still have attendance logged, proctors
   assigned, supplies delivered, or accommodations arranged as if it existed
   (the downstream-side counterpart of ghost/proxy logistics). A session
   lacking a venue or start is rejected outright — never held, never
   auto-committed at Phase 3.



21. **Proctor is not an enrolled test-taker** — a
   `:coordinate-proctor-assignment-proposal` must not name a proctor who is
   also a member of the target session's enrolled roster. HARD CHECK 4
   (impartiality) only inspects the DECLARED `:proctor-impartial?` boolean,
   and HARD CHECK 6 (enrollment binding) only binds test-taker names on
   attendance/accommodation ops — never proctor ids. So a session's own
   enrolled test-taker could be named as its own proctor, pass checks 1–20
   with a declared-impartial flag, and auto-commit at Phase 3 (the same id
   both seated examinee and supervisor — an anti-impersonation /
   ghost-seating vector). HARD CHECK 21 closes it: a named proctor that is an
   enrolled test-taker of the session is rejected outright, never held, never
   auto-committed at Phase 3.
22. **Attendance reconciliation** — a `:log-attendance-note` must account for EVERY test-taker enrolled to the target session: each roster member must appear in `:check-in` or in `:absent`. HARD CHECK 6 (enrollment binding) only requires every NAMED id to be a roster member (it constrains the subset, never the whole), HARD CHECK 8 requires the two sets to be disjoint, and HARD CHECK 14 forbids duplicating an id — but none requires FULL coverage. A partial note that leaves an enrolled test-taker out of both sets keeps that person's status UNDEFINED in the paper trail, yet the note would auto-commit at Phase 3 as if attendance were complete (the ghost-seating counterpart on the attendance side of HARD CHECK 21's proctor rule). An incomplete note is rejected outright — never held, never auto-committed at Phase 3.

23. **No duplicate safety-concern category** — a `:flag-safety-concern` must
   not name the same safety-concern category more than once. HARD CHECK 11 only
   requires every declared category to be one of the closed set (facility /
   wellbeing / integrity / environment concerns); it never guards against the
   SAME category being declared twice. Repeating a category is the safety-side
   analog of HARD CHECK 13's duplicated proctor, HARD CHECK 14's duplicated
   attendance id, HARD CHECK 15's duplicated supply item, and HARD CHECK 16's
   duplicated accommodation category: it inflates the nominal number of
   distinct concerns — and thus the escalation/triage workload — without adding
   an actual separate incident to route. A duplicated safety-concern category
   is rejected outright — never held, never auto-committed at Phase 3.

24. **Proctor id non-blank** — a
   `:coordinate-proctor-assignment-proposal` must name only proctors with a
   NON-BLANK `:proctor/id`. HARD CHECK 10 requires the list to be non-empty,
   HARD CHECK 13 requires the ids to be distinct, and HARD CHECK 21 forbids an
   enrolled test-taker — but none rejects an entry whose id is nil, `""`, or
   whitespace-only. A map-form `{:proctor/id "" :testadmn.proposal/proctor-impartial? true}`
   passes checks 4/10/13/21 and, at Phase 3, would auto-commit a supervisor with
   NO identity — the same fabricated-body inflation as a duplicated id, but
   achieved via blindness instead of repetition. A blank proctor id is rejected
   outright — never held, never auto-committed at Phase 3.

25. **No venue-time double-booking** — ISIC-855 one room hosts ONE exam at a
   time. The target session of a `:schedule-test-session` must not share its
   (`:facility-id`, `:scheduled-start`) pair with any OTHER registered session
   in the store. HARD CHECK 12 (schedule-verified) only requires the venue and
   the start to be PRESENT, and HARD CHECK 18 only requires an enrolled roster
   — but none asks whether that room is already OCCUPIED at that instant. A
   schedule that books `facility-101` at `2026-07-15T09:00:00Z` while
   `facility-101` is already registered for a different exam at exactly that
   time passes checks 1–24 and, at Phase 3, would auto-commit two exams into
   one room — a collision that only surfaces physically at check-in, when the
   second test-taker queue arrives. A blank venue or start cannot mask the
   conflict: such a target is rejected earlier by HARD CHECK 12, so only
   concrete (venue, time) pairs can collide. A double-booked schedule is
   rejected outright — never held, never auto-committed at Phase 3.

26. **No roster-member time collision** — ISIC-855 one body sits ONE exam at a
    time. HARD CHECK 25 keeps one *room* out of two simultaneous exams; this
    check keeps one *person* out of two. The target session of a
    `:schedule-test-session` must not share an enrolled test-taker with any
    OTHER registered session starting at the same instant
    (`:testadmn.test-session/scheduled-start`). HARD CHECK 12 makes the start
    concrete, HARD CHECK 18 makes the roster non-empty, and HARD CHECK 25
    separates the rooms — but a student enrolled to the 09:00 SAT in
    `facility-101` and the 09:00 ACT in `facility-204` passes 1–25 (the
    venue-time pairs differ, so the room check never fires) and would
    auto-commit at Phase 3 an impossible double-seating that surfaces only at
    check-in, when the test-taker cannot be in two rooms at once. A target
    without a concrete start is rejected earlier by HARD CHECK 12, so only
    real instants can collide. A colliding roster is rejected outright — never
    held, never auto-committed at Phase 3.

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