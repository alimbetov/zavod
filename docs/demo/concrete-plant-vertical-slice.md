# Zavod demonstration V1 — Concrete Plant / Engineering + SIMULATED telemetry

Date: 2026-11-13
Scope: **DEV-LOCAL educational engineering demonstration**, not an industrial HMI, SCADA, MES, production safety controller or plant commissioning.
Branch: `feat/bcq-g4-command-reconciliation`, PR #19 remains DRAFT/HOLD pending independent review.

## Goal: one reproducible customer-facing product walkthrough

A three-line concrete plant generated using the established typed `GuidedBlueprintBuilder.concreteStarter`, retained in PostgreSQL, with actual server-owned lifecycle and simulated sensor evidence:

1. Create local site → choose `+ Бетонный завод` → Validate → Save Draft.
2. `Согласовать (DEMO)` uses **real** `BlueprintLifecycleService.approve` and an explicitly **synthetic** reviewer (never a human signature).
3. `Опубликовать` uses distinct synthetic publisher and production publish contract (four-eyes business check still runs).
4. `Выбрать активную версию` invokes actual `ActivationCommandService.activate`, with stable per-revision idempotency key, PostgreSQL receipt, immutable activation history and hash ledger. No physical device is touched or commanded.
5. `Штатный сигнал` persists `SIMULATED` reading `2.5 bar` for the concrete mixing water doser through the existing telemetry registry and ingestion service.
6. `Учебная тревога` persists `SIMULATED` `6.2 bar` for the same signal. React monitoring highlights a **demo-only** threshold `>= 5 bar`. This is not a certified pressure limit or equipment alarm.
7. Refresh the browser: approval/publish/selection status, telemetry history, and audit event count must come from persisted PostgreSQL, not UI-only flags. No fabricated CURRENT if server returns NO_DATA/UNTRUSTED/STALE.

## Server implementation and safety boundary

- `PlantDemoDevController` has explicit `@Profile("dev-local")` and only accepts `local-<uuid>` sites and the expected three-line concrete Blueprint. A production/default profile rejects all HTTP requests through `SiteSecurityConfiguration`.
- `application-dev-local.properties` binds server address to `127.0.0.1`; DEV security filter requires loopback client, trusted Origin and `X-Zavod-Dev-Write: true` for all writes.
- Synthetic role identities (`synthetic-demo-reviewer`, `synthetic-demo-publisher`, `synthetic-demo-activator`) are created **only in local DEV DB** and are explicitly displayed as simulated; they cannot be represented as independent human engineering approvals.
- Signal source is `SIMULATED` only. `EDGE_READ_ONLY` and physical PLC/OT commands are not accepted by this endpoint. UI keeps the engineering-only, no-PLC warning.
- The engineering activation set is a database pointer; it **does not** activate physical production equipment.
- API mutations are explicitly staged and separately auditable. No automatic approve/publish/activate hidden behind a single magic "demo success" button.
- The latest reading's quality is evaluated by the backend. The presentation-layer `demo-pressure` threshold is illustrative only; it cannot certify process limits.

## Verification

- `PlantDemoDevApiPostgresTest`: actual PostgreSQL through MockMvc; create populated concrete Blueprint, role-separated approve/publish, activate, retry with no duplicate audit, store nominal/alarm readings, verify signal history and version binding, full ledger validation, reject unauthenticated-DEV-style writes and invalid scenario.
- `web/e2e/real-backend.spec.ts` test `G4 demo: real lifecycle, synthetic alarm, PostgreSQL audit and reopen`: full React user flow backed by Spring/PostgreSQL, verifies sample values, alarm and history after browser reload.
- Existing four CI jobs: Java/PostgreSQL, PostgreSQL recovery/PITR, React, real backend browser E2E.

## Pilot/customer demonstration boundaries

The project has **not** been approved for production, industrial telemetry, OT gateways, PLC command issuing, safety alarms, SIL/PL claims or physical commissioning. This vertical slice is a **sales/product discovery demonstration**: show how users model and version a facility, control engineering lifecycle, and investigate explicitly simulated telemetry.

Next product milestone should focus on customer feedback from this one workflow: step clarity, model terminology, permission design, alarm semantics, and workload. An industrial read-only pilot requires a separately reviewed security/OT design and operator approval. ML remains out of scope.
