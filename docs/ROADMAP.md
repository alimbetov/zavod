# Zavod IndustrialOS — Product Roadmap and Release Qualification

**Status:** DRAFT · 2026-11-13  
**Source:** [R0 Master Specification](spec/R0-MASTER-SPEC.md)

## Product north star

A small or medium plant can be **digitally described and connected without replacing a functioning PLC**, safely visualize telemetry, manage production orders and recipes, and issue reliable batch records with traceability.

The first measurable business outcome is not feature count: **one real plant demonstrates reduced manual re-entry and attributable material usage/production records while preserving independent PLC operation**.

## Releases and Definition of Done

| Release | Scope | Evidence / exit gate |
|---|---|---|
| R0 — Designer foundation | Equipment registry, explicit physical ports, material-flow graph, engineering validation, versioned JSON, undo/redo | Deterministic unit tests, Playwright journeys, graph migration tests, reviewed schema |
| R1 — Core | Java backend, PostgreSQL schema, plant-scoped RBAC, revision/approval lifecycle, audit | Testcontainers, authz negative tests, migrations, backup/restore dry-run, API contract tests |
| R2 — SCADA read-only | OPC UA + Modbus TCP via Edge, tag dictionary, quality/staleness, trend/HMI, alarms, offline buffering | Simulator and target PLC tests, outage and replay, performance and security tests, no-write assertions |
| R3 — MES concrete pilot | Recipes, orders, batch journal, mass variance, stock reconciliation, shift reports | Batch traceability, fixed recipe revision, deviation workflows, reconciliation under restarts |
| R4 — Limited command mode (separate gate) | Whitelisted operator intents, controller acknowledgements, local permissives | Site-specific hazard analysis, qualified PLC engineer, FAT/SAT, permissions, fault-injection and audit |
| R5 — Multi-site | Fleet dashboard, MQTT/Sparkplug B, other vertical templates, ERP APIs | Multi-site isolation, data retention and upgrades verified |

## Workstream backlog

### Workstream A — Engineering Designer
- A-01 Device-type schema, physical ports, material unit/medium taxonomy
- A-02 Graph validator (connection, cardinality, feed completeness, loops)
- A-03 Revision compatibility and migrator
- A-04 Engineering diff, undo/redo, non-destructive draft recovery
- A-05 UI accessibility, localization RU/KZ/EN, E2E

### Workstream B — Industrial Connectivity
- B-01 Simulator with deterministic fake signals and connection failures
- B-02 OPC UA adapter, reconnect and quality
- B-03 Modbus TCP adapter, polling and scaling
- B-04 Durable store-and-forward telemetry and idempotent ingestion
- B-05 Inventory of device firmware/protocol support
- B-06 PLC read-only integration lab with real controller

### Workstream C — SCADA Operations
- C-01 Live value overlay on approved scene
- C-02 Signal historian and time-series views
- C-03 Alarm model: raise, ack, clear, priority, audit
- C-04 Connection quality and stale-data visual guard
- C-05 Runbooks and local reliability test

### Workstream D — MES Concrete
- D-01 Recipe ingredient and version approvals
- D-02 ProductionOrder
- D-03 BatchExecution and immutable batch record
- D-04 Weighing tolerances and quality disposition
- D-05 Inventory movements/reconciliation and shift accounting
- D-06 Reporting and operator daily workflow

### Workstream E — Security / qualification
- E-01 Plant-scoped authorization
- E-02 OT zoning reference architecture and remote-access control
- E-03 Command disabled-by-default policy
- E-04 Integration, resilience and performance benches
- E-05 FAT/SAT documentation and external PLC/safety reviewer
- E-06 Upgrade/recovery/backup procedures

## Top 10 RED scenarios for next sprint

1. Mixing unit separately accepts WATER and ADDITIVE inlet connections.
2. Same physical inlet cannot be occupied twice unless multi-connection capability explicitly allows it.
3. Attempt to connect GRAVEL source to WATER inlet is rejected.
4. Incomplete mandatory material paths block publish and demo-run.
5. Cycles are invalid except where equipment explicitly permits recirculation.
6. Legacy v1/v2 JSON import retains all supported semantics or reports explicit migration errors.
7. Invalid saved draft remains retrievable rather than overwritten with demo.
8. Graph validation returns deterministic diagnostics with port and equipment IDs.
9. Save/reopen/export/import results in semantically equivalent topology.
10. Without approved activation an engineering draft cannot appear as an operational HMI.

## Pilot validation order

1. Select one concrete plant installation and collect instrumentation list, process diagram, PLC make/model/firmware, registers/tags, and integrator contact.
2. Confirm no-write read-only access using an isolated lab and network risk assessment.
3. Instrument meaningful production signals (recipe ID, scale actuals, mixers, batch state, alarm states).
4. Operate in parallel with existing HMI/PLC for a documented observation period. No change to control logic.
5. Reconcile Zavod batch reports against plant/scale logs, investigate differences and quality issues.
6. Decide whether to proceed with limited qualified control **only** if an owner/integrator/site safety review approves it.

## Metrics to prove value

- **Integration lead time**: days from documented protocol/tag list to live read-only HMI.
- **Batch traceability coverage**: % of batches with complete recipe/version/actual component log.
- **Reconciliation accuracy**: absolute discrepancy vs accepted calibrated source measurements, including uncertainty.
- **Operator administrative time**: time per shift spent on manual entry before/after.
- **Fault visibility**: median time from simulated disconnection to stale status/alarm.
- **No-loss buffering**: events delivered after a bounded offline interval with de-duplication.
- **Safety**: zero unapproved writes, independent PLC safety operation demonstrated.

Targets must be established with pilot hardware and processes; numerical goals are not presented as achieved product results.

## Decisions for the first design review

1. Target concrete plant topology + allowed equipment templates.
2. PLC brand/model/firmware and site engineering owner.
3. Units, calibration and acceptance tolerances for materials.
4. First historical retention and telemetry sampling profile.
5. Can the existing HMI co-exist as the system of operations?
6. Allowed languages and data residency requirements.
7. First scope of alarms and quality-related records.
8. Exact approval authority for engineering configuration activation.
