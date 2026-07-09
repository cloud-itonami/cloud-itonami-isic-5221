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

## License

AGPL-3.0-or-later.
