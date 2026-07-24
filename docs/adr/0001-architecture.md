# ADR-0001: Land Transport Support Advisor ⊣ Land Transport Support Governor architecture

## Status

Accepted. `cloud-itonami-isic-5221` promoted from `:blueprint` to
`:implemented` in the `kotoba-lang/industry` registry.

## Context

`cloud-itonami-isic-5221` publishes an OSS business blueprint for community
land-transport support operations (toll-road/highway operation, bus terminal
operation, and towing/vehicle-recovery services provided to/on behalf of
multiple carriers and motorists). Like every prior actor in this fleet, the
blueprint alone is not an implementation: this ADR records the governed-actor
architecture that promotes it to real, tested code, following the same
langgraph StateGraph + independent Governor + Phase 0->3 rollout pattern
established by `cloud-itonami-isic-6511` and applied by
`cloud-itonami-isic-5210` (terminal storage) most recently.

Like the terminal-storage sibling, this vertical has NO bespoke domain
capability library in `kotoba-lang` to wrap for its dispatch-precondition or
recovery-capacity checks (verified: no `kotoba-lang/land-transport-support`-
style repo exists). This build therefore uses self-contained domain logic --
the recovery-capacity check (recovered vehicle weight vs a tow vehicle's
rated capacity) lives as a pure function in `landtransport.registry` and is
re-verified independently by the governor.

An earlier commit to this repository (`a51bf77`, "Implement landsupport
actor") took a materially different, narrower design: a closed four-op
"operations coordination" allowlist (facility-record logging, maintenance
scheduling, structural-safety-concern flagging, supply-order coordination)
that deliberately never modeled a toll-lane / recovery-job / terminal-slot
DISPATCH or a RECONCILIATION record at all -- every op in that design
carried `:effect :propose` and none of them corresponded to this
blueprint's own README Core Contract text ("dispatch, clearance record,
reconciliation record, or human approval"). This ADR's design supersedes
that commit: it models the entity and ops the blueprint's own README and
`docs/business-model.md` actually promise (a `land-dispatch` record,
`:dispatch/authorize`, `:reconciliation/publish`), and follows the fleet's
strict 8-file template (`facts.cljc` + `registry.cljc` as dedicated,
separate namespaces) that the superseded commit did not use. The
superseded `landsupport.*` module was removed in the same commit that
added this `landtransport.*` module; nothing from it carries forward.

## Decision

### Decision 1: fresh governor identity, no reuse precedent needed

`:land-transport-support-governor` is the identity already declared in this
repo's `blueprint.edn`. This build follows the SAME governed-actor
architecture as every prior actor, with its own distinct governor identity.

### Decision 2: self-contained domain logic (no `kotoba-lang/land-transport-support` to wrap)

Like `cloud-itonami-isic-5210`, this vertical has NO pre-existing capability
library to delegate the dispatch-precondition or recovery-capacity checks
to. The recovery-capacity check (recovered vehicle weight vs a tow
vehicle's rated capacity) is therefore a pure function defined in
`landtransport.registry` and called directly by `landtransport.governor` --
the SAME 'reuse a capability's own validated function' discipline
`retailops.governor`'s ean13 check establishes, here applied to this
vertical's OWN pure registry function.

### Decision 3: a single `land-dispatch` entity, three dispatch kinds

Rather than three separate entity types for toll-lane / recovery-job /
terminal-slot dispatches, this build uses ONE `land-dispatch` entity with a
`:dispatch-kind` discriminator (`:toll-lane` | `:recovery-job` |
`:terminal-slot`). Fields meaningful to only one kind
(`:vehicle-condition-checked?`, `:tow-vehicle-max-capacity-kg`,
`:recovered-vehicle-weight-kg` for `:recovery-job`;
`:terminal-slot-evidence-verified?` for `:terminal-slot`) are simply unused
for the other kinds, mirroring how `terminal.store`'s tank entity carries
fields not every check consults (e.g. `:gauge-verified?`, never itself
HARD-checked). `landtransport.governor/dispatch-precondition-violations`
switches on `:dispatch-kind`; an unrecognized/missing kind is
conservatively treated as a violation (missing-data-is-unsafe).

### Decision 4: dual-actuation shape, SEQUENTIAL on the SAME `land-dispatch` entity

Like the terminal-storage sibling's `commit` then `transfer` shape, this
vertical's `dispatch-authorize` and `reconciliation-publish` actuation
events apply SEQUENTIALLY to the SAME `land-dispatch` record -- a dispatch
is authorized first, a reconciliation record is published later, on the
same record. `high-stakes` is `#{:dispatch/authorize
:reconciliation/publish}`; neither ever auto-commits at any phase.

### Decision 5: the recovery-capacity check -- an honest reapplication of the fabrication value-vs-rated-limit discipline

`recovery-capacity-exceeded?` reapplies the **fabrication measured-value-
vs-rated-limit** discipline (the same discipline
`terminal.registry/overfill-risk?` reapplies to tank ullage) to a recovered
vehicle's weight vs a tow vehicle's rated capacity -- a real, well-known
gross-vehicle-weight-vs-tow-capacity towing-safety hazard. It returns
`true` (unsafe) when the value is provably outside the safe envelope, or
when either input is missing (cannot verify safe to dispatch). Documented
as a discipline-reapplication, not claimed as a novel invention, per
`cloud-itonami-isic-0162` Decision 3's convention.

### Decision 6: dedicated double-actuation-guard booleans

`:dispatched?` / `:reconciliation-published?` are dedicated booleans on the
`land-dispatch` record, never a single `:status` value -- the same
discipline every prior governor's guards establish, informed by
`cloud-itonami-isic-6492`'s real status-lifecycle bug (ADR-2607071320).

### Decision 7: `MemStore` only at R0

`landtransport.store/Store` ships one backend, `MemStore`, at R0. Several
sibling actors in this fleet ship MemStore-only at their own R0 for the
same reason: proving the governed-actor contract (advisor ⊣ governor ⊣
phase ⊣ audit ledger) does not require a second backend on day one. A
`DatomicStore` (`langchain.db`-backed, mirroring `terminal.store`'s own
pair) is the natural next seam against the SAME protocol, deferred rather
than built speculatively.

### Decision 8: Phase 0->3 with `:dispatch/authorize`/`:reconciliation/publish` NEVER auto

`landtransport.phase`'s phase table puts `:safety-scope/intake` (no
dispatch risk) in phase 3's `:auto` set as its only member;
`:safety-scope/verify`, `:dispatch/authorize` and
`:reconciliation/publish` are never auto-eligible at any phase.
`landtransport.governor`'s high-stakes gate enforces the
`:dispatch/authorize`/`:reconciliation/publish` half of that invariant
independently: two layers agree that dispatch authorization and
reconciliation publication are always a human operator's call.

### Decision 9: mock advisor only at R0

`landtransport.landtransportadvisor` provides a deterministic
`mock-advisor` (default, runs offline) and an `llm-advisor` backed by a
`langchain.model/ChatModel`, following the SAME defensive-parse discipline
`terminal.terminaladvisor` establishes: any parse/shape failure yields a
safe low-confidence noop so the governor escalates/holds. R0 exercises the
`mock-advisor` path only; wiring a real model is a follow-up, not required
to prove the governed-actor contract.

## Alternatives considered

- **Keeping the superseded `landsupport.*` "operations coordination"
  design.** Rejected: it never modeled a dispatch or a reconciliation
  record at all, which the blueprint's own README and business-model docs
  explicitly promise, and it did not follow the fleet's strict facts.cljc +
  registry.cljc template. Superseding it (rather than running both designs
  side by side under different namespaces in the same repo) keeps the
  repository's domain model singular and honest.
- **Three separate entity types for toll-lane / recovery-job /
  terminal-slot.** Rejected in favor of one `land-dispatch` entity with a
  `:dispatch-kind` discriminator -- the three kinds share the majority of
  their lifecycle (intake, safety-scope verification, dispatch
  authorization, reconciliation publication); only the dispatch-
  authorization precondition and the recovery-capacity check are kind-
  specific.
- **Building a `DatomicStore` backend in this R0.** Rejected in favor of a
  scoped R0 slice (`MemStore` only) -- the `Store` protocol is designed so
  a second backend is a pure addition, not a rewrite, when needed.
- **Building terminal-berth/lane-dispatch scheduling optimization in this
  R0.** Rejected in favor of a scoped R0 slice (the blueprint's own
  `:optimization` technology is correctly marked optional; the integration
  is a follow-up), consistent with this fleet's 'extending coverage is
  additive' convention.

## Consequences

- A fresh independent build of the SAME governed-actor architecture
  (langgraph StateGraph + independent Governor + Phase 0->3 rollout), with
  its own distinct `:land-transport-support-governor` identity, faithful to
  this blueprint's own README Core Contract (dispatch / reconciliation
  record / human approval).
- Establishes the recovery-capacity check as an honest reapplication of the
  fabrication value-vs-rated-limit discipline to land-transport-support, no
  genuinely-new-concept check, documented as such per
  `cloud-itonami-isic-0162` Decision 3.
- Supersedes and removes the earlier `landsupport.*` module in the same
  commit that lands this `landtransport.*` module.
- `blueprint.edn`'s `:itonami.blueprint/id` is corrected from
  `"cloud-itonami-5221"` to `"cloud-itonami-isic-5221"` (matching the repo's
  actual directory name and the fleet convention its own siblings use), and
  `:itonami.blueprint/maturity :implemented` is added.

## References

- `cloud-itonami-isic-6511/docs/adr/0001-architecture.md` (origin of the
  general governed-actor architecture pattern)
- `cloud-itonami-isic-5210/docs/adr/0001-architecture.md` (terminal-storage
  sibling; same self-contained-domain-logic, sequential dual-actuation
  shape, MemStore/DatomicStore-seam pattern)
- `cloud-itonami-isic-0162/docs/adr/0001-architecture.md` (origin of the
  'honest reapplication, documented as such' convention this build follows
  for its recovery-capacity check)
- 道路整備特別措置法 (Road Improvement Special Measures Act, toll-road
  concessions) (Japan, 国土交通省 / 都道府県公安委員会)
- State DOT toll-authority statutes; state-licensed towing/recovery
  programs, e.g. California's Consumer Automotive Recovery Program (US)
- DVSA-recognized vehicle-recovery operator scheme; UK toll-concession
  frameworks (UK, Highways England / DVSA)
