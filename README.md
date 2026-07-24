# cloud-itonami-5221

Open Business Blueprint for **ISIC Rev.5 5221**: service activities
incidental to land transportation (toll-road/highway operation, bus
terminal operation, and towing/vehicle-recovery services).

This repository designs a forkable OSS business for community
land-transport support operations: toll/terminal-safety-scope
management, robotics-assisted lane/toll-gantry inspection and
vehicle-recovery dispatch, and multi-carrier booking/reconciliation
records — run by a qualified operator so a toll-road authority, bus
terminal operator or towing company keeps its own safety-
certification and dispatch history instead of renting a closed
land-transport-support platform.

## Scope note: land-transport support, not carriage or inspection consulting

`cloud-itonami-isic-4911`/`4912` (passenger/freight rail) and
`cloud-itonami-isic-4920` (road freight) are CARRIERS — businesses
that move goods or people aboard their own vehicle. This repository is
deliberately scoped to the SEPARATE business of land-transport support
infrastructure and services: toll-road/highway operation, bus
terminal operation and vehicle-recovery/towing, provided to or on
behalf of MULTIPLE carriers and motorists under their own independent
licensing regime (toll-road concessions under Japan's 道路整備特別措置法
Road Improvement Special Measures Act; US state DOT toll-authority
statutes and EU PPP concession frameworks; state-licensed towing
operators such as California's Consumer Automotive Recovery Program
or the UK's DVSA-recognized recovery operators; bus terminal operators
regulated separately from bus carriers in most jurisdictions). Also
distinct from `cloud-itonami-cofog-04.5` ("Independent Road & Bridge
Inspection Robotics"): that business is a THIRD-PARTY inspection
vendor that SELLS pavement/bridge condition surveys to toll-road
authorities (among other public-works customers) -- it does not
operate a toll road, bus terminal or towing service itself. This
repository is the operator, not the inspector.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here robots (toll-gantry/lane
inspection, vehicle-recovery dispatch support, terminal-berth
monitoring) operate under an actor that proposes actions and an
independent **Land Transport Support Governor** that gates them. The
governor never dispatches a toll-lane change, recovery job or terminal
slot itself; `:high`/`:safety-critical` actions (any dispatch outside
verified safety scope, a recovery job without a completed vehicle-
condition check, a terminal-slot record without verified evidence)
require human sign-off.

## Core Contract

```text
intake + identity + toll/terminal safety scope + booking
        |
        v
Land Transport Support Advisor -> Land Transport Support Governor -> dispatch, clearance record, reconciliation record, or human approval
        |
        v
robot actions (gated) + dispatch record + reconciliation record + audit ledger
```

No automated advice can dispatch a toll-lane, recovery job or
terminal-slot operation the governor refuses, issue a clearance record
outside its verified scope, or publish a reconciliation record without
governor approval and audit evidence.

## Capability layer

Resolves via [`kotoba-lang/industry`](https://github.com/kotoba-lang/industry)
(ISIC `5221`). Implemented by:

- [`kotoba-lang/robotics`](https://github.com/kotoba-lang/robotics) — missions, actions, safety-stops, telemetry proofs
- [`kotoba-lang/logistics`](https://github.com/kotoba-lang/logistics) — booking, transit, delivery/reconciliation contracts

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## Why an actor layer at all?

An LLM is great at drafting a dispatch summary, normalizing safety-
scope intake records, and reading a booking log -- but it has **no
notion of which jurisdiction's toll/terminal safety-scope law is
official, no license to authorize a toll-lane change, a recovery-job
dispatch or a terminal-slot operation, and no way to know on its own
whether a recovery job's vehicle-condition check was actually
completed, whether a terminal-slot operation's evidence was actually
verified, or whether a recovered vehicle actually fits the tow
vehicle's rated capacity**. Letting it authorize a dispatch or publish
a reconciliation record directly invites fabricated regulatory
citations, a recovery job dispatched without a completed vehicle-
condition check, an unverified terminal-slot record, and an overloaded
tow vehicle -- exposing motorists, recovery crews and carriers to real
harm and the operator to real liability, for whoever runs it. This
project seals the Land Transport Support Advisor into a single node
and wraps it with an independent **Land Transport Support Governor**,
a human **approval workflow**, and an immutable **audit ledger**.

## Actuation

**Authorizing a toll-lane / recovery-job / terminal-slot dispatch and
publishing a multi-carrier reconciliation record are never autonomous,
at any phase, by construction.** Two independent layers enforce this
(`landtransport.governor`'s `:dispatch/authorize`/
`:reconciliation/publish` high-stakes gate and `landtransport.phase`'s
phase table, which never puts either op in any phase's `:auto` set) --
see `landtransport.phase`'s docstring and
`test/landtransport/phase_test.clj`'s
`dispatch-authorize-never-auto-at-any-phase`/
`reconciliation-publish-never-auto-at-any-phase`. The actor may draft,
check and recommend; a human operator is always the one who actually
authorizes a dispatch or publishes a reconciliation record.

## Run

```bash
clojure -M:dev:run     # walk one clean authorize + reconcile lifecycle, plus HARD-hold cases, through the actor
clojure -M:dev:test    # governor contract · phase invariants · store contract · registry conformance · facts coverage
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Layout

| File | Role |
|---|---|
| `src/landtransport/store.cljc` | **Store** protocol -- `MemStore` (R0; `DatomicStore` is the deferred next seam) + append-only audit ledger + dispatch-authorization AND reconciliation-publish history (dual history). The double-actuation guard checks dedicated `:dispatched?`/`:reconciliation-published?` booleans rather than a `:status` value |
| `src/landtransport/registry.cljc` | Dispatch-authorization/reconciliation-publish draft records, plus the self-contained tow-vehicle recovery-capacity range-check pure function (`recovery-capacity-exceeded?`) the governor re-verifies against -- no external capability library to delegate to |
| `src/landtransport/facts.cljc` | Per-jurisdiction toll/terminal safety-scope catalog with an official spec-basis citation per entry (JPN/USA/GBR), honest coverage reporting |
| `src/landtransport/landtransportadvisor.cljc` | **Land Transport Support Advisor** -- `mock-advisor` ‖ `llm-advisor`; intake/safety-scope-verification/dispatch-authorization/reconciliation-publish proposals |
| `src/landtransport/governor.cljc` | **Land Transport Support Governor** -- 6 HARD checks (spec-basis · evidence-incomplete · dispatch-precondition-unmet (recovery-job vehicle-condition check / terminal-slot verified evidence) · recovery-capacity-exceeded, the fabrication value-vs-rated-limit discipline · already-dispatched · already-reconciled) + 1 soft (confidence/actuation gate) |
| `src/landtransport/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted verify → supervised (dispatch/reconciliation always human; safety-scope intake is the ONLY auto-eligible op, no dispatch risk) |
| `src/landtransport/operation.cljc` | **OperationActor** -- langgraph StateGraph |
| `src/landtransport/sim.cljc` | demo driver |
| `test/landtransport/*_test.clj` | governor contract · governor unit checks · phase invariants · store contract · registry conformance · facts coverage |

## Business-process coverage (honest)

This actor covers safety-scope intake through per-jurisdiction evidence
assessment, dispatch authorization (toll-lane / recovery-job /
terminal-slot) and reconciliation-record publication -- the core
governed lifecycle:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Safety-scope intake + per-jurisdiction evidence checklisting, HARD-gated on an official spec-basis citation (`:safety-scope/intake`/`:safety-scope/verify`) | Real robot/toll-gantry telemetry and dispatch integration, an `llm-advisor` actually wired to a live model (the code path exists; R0 ships `mock-advisor` only) |
| Dispatch authorization, HARD-gated on full safety-scope evidence, the dispatch-kind-specific precondition (a completed vehicle-condition check for a recovery job; verified evidence for a terminal-slot operation), a recovery-capacity range check, plus a double-dispatch guard (`:dispatch/authorize`) | A `DatomicStore` backend (the `Store` protocol is designed for it -- see `landtransport.store` docstring -- but only `MemStore` ships at R0) |
| Reconciliation-record publication, HARD-gated on full evidence and no double-publish (`:reconciliation/publish`) | Carrier/motorist billing settlement itself, terminal-berth/lane-dispatch scheduling optimization (the blueprint's own `:optimization` technology) |
| Immutable audit ledger for every intake/verification/authorization/reconciliation decision | |

Extending coverage is additive: add the next gate (e.g. a vapor-
recovery-lane or berth-scheduling check) as its own governed op with
its own HARD checks and tests, following the SAME "an independent
governor re-verifies against the actor's own records before any
real-world act" pattern this repo's flagship ops already establish.

## Jurisdiction coverage (honest)

`landtransport.facts/coverage` reports how many requested
jurisdictions actually have an official spec-basis in
`landtransport.facts/catalog` -- currently **3 seeded (JPN, USA, GBR)**
out of ~194 jurisdictions worldwide. This is a starting catalog to
prove the governor contract end-to-end, not a claim of global
coverage. Adding a jurisdiction is additive: one map entry in
`landtransport.facts/catalog`, citing a real official source -- never
fabricate a jurisdiction's requirements to make coverage look bigger.

## Maturity

`:implemented` -- `Land Transport Support Advisor` + `Land Transport
Support Governor` run as real, tested code (see `Run` above),
promoted from the originally-published `:blueprint`-tier scaffold,
following the SAME governed-actor architecture as the other prior
actors across this fleet, with its own distinct, independently-named
governor and its own self-contained recovery-capacity check. See
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for
the full architecture and decision record.

## License

AGPL-3.0-or-later.
