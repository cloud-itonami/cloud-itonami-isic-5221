# Operator Guide

## First Deployment
1. Register operator, toll plaza/terminal/depot, safety scope, staff
   and robots.
2. Import existing multi-carrier terminal-slot booking and billing
   history.
3. Run read-only safety-scope and toll-gantry/lane-inspection robot
   mission dry-runs.
4. Configure safety-class allowed sets and human sign-off paths.
5. Publish a dry-run reconciliation record and audit export.

## Minimum Production Controls
- toll/terminal safety-scope validation before any dispatch
- governor gate on every robot action before dispatch
- human sign-off for :high/:safety-critical actions (out-of-scope
  dispatch, an unchecked recovery job, an unverified terminal-slot
  record)
- evidence-backed reconciliation records
- audit export for every dispatch, sign-off and reconciliation record
- backup manual land-transport-support process

## Certification
Certified operators must prove robot-safety integrity, safety-scope
discipline, evidence-backed reconciliation records and human review
for dispatch-affecting actions.
