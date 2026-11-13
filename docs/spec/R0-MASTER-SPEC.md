# Zavod IndustrialOS — Master Technical Specification R0

**Status:** DRAFT / Proposed for review  
**Date:** 2026-11-13  
**Repository:** https://github.com/alimbetov/zavod  
**Initial domain:** concrete batching plant (БСУ / РБУ)  
**Long-term domain:** controller-independent industrial line configuration, supervisory monitoring and manufacturing operations  
**Decision model:** Scenario Design → RED tests → Test Review → Implementation → Qualification → pilot acceptance

> This is the target specification, **not** a declaration of implemented code. The earlier standalone Line Designer R0.1 prototype has not yet been imported or independently qualified in this repository.

## 1. Vision and product boundaries

Zavod IndustrialOS is an **on-premise-first** and extensible industrial software platform consisting of:

1. **Zavod Designer** — graphical engineering editor for equipment, physical ports, material flows, control tags, line and screen definitions.
2. **Zavod SCADA** — supervisory data acquisition, HMI, alarms, status, time series and operator workflows.
3. **Zavod MES** — recipes, production orders, batch records, material consumption, quality, downtime and shift reporting.
4. **Zavod Edge** — protocol adapters and controlled integration with existing industrial systems.
5. **Zavod Fleet** (later) — centralized, multi-plant analytics and remote diagnostics, never required for local safe operation.

**Product wedge:** retrofit of existing small/medium batch plants, without mandatory replacement of an operational PLC, with a modern operator UI and consolidated manufacturing records.

**Non-goals in initial releases:** replacement of PLC firmware; safety instrumented systems; hard real-time motor control in Java; unrestricted remote PLC writes; arbitrary dynamic code execution; fully general ERP; a full physics simulator; instant support for every proprietary protocol.

## 2. Industrial architecture and responsibility boundary

Physical process and safety-critical automation belong to **PLC + dedicated local protective controls**. Zavod is a supervisory and manufacturing application. It must not be in the required path for emergency stopping, machinery guards, overload protection, emergency shutdown, or deterministic process loops.

| Layer | Responsibility | Zavod component |
|---|---|---|
| ISA-95 L0/L1 | Sensors, drives, valves, motors, process | Existing plant assets |
| Control domain | Deterministic sequences, permissives, safe stop | Site PLC / safety circuits |
| Supervisory (L2) | Visualization, commands after authorization, alarms | Zavod SCADA + on-prem Edge |
| Manufacturing (L3) | Recipes, orders, batches, quality, stock accounting | Zavod MES |
| Enterprise (L4) | ERP, finance, fleet, procurement | External integrations / later Fleet |

**Mandatory deployment rules:**
- The local control system continues safe operation when backend, historian, browser, WAN, cloud or Edge is unavailable.
- Default PLC integration is **read-only**. Command functionality is a separate gated release after independent site-specific engineering review, hazardous-situation analysis and factory/site acceptance.
- No open inbound route from public Internet/cloud to PLC networks; use segmented OT zones, least privilege, controlled outbound flows and audited remote maintenance.
- Operator UI may issue approved intent requests only; Edge validates operator/session/plant/revision/command policy, and PLC performs final permissive and interlock checks.
- Clearly show communication quality (GOOD/UNCERTAIN/BAD/STALE), local timestamps and controller state; never present a stale value as current.

## 3. Users and privileges

| Role | Permitted responsibilities |
|---|---|
| PLATFORM_ADMIN | Manage deployment and platform policy; no implicit physical control |
| PLANT_ADMIN | Plant configuration, authorized users and site policies |
| ENGINEER | Design/edit equipment, port topology and mappings |
| TECHNOLOGIST | Create and approve recipes and tolerances |
| OPERATOR | View HMI, acknowledge alarms, run authorized production workflows |
| MAINTENANCE | Diagnostics, service and authorized maintenance workflows |
| SHIFT_SUPERVISOR | Shift oversight, production review, limited approvals |
| AUDITOR / VIEWER | Read-only audit, reporting and evidence |

Enforce server-side RBAC **and plant/line scoped authorization**. Every configuration activation and sensitive operation generates an immutable audit event with principal, timestamp, before/after revision, source and outcome.

## 4. Industrial domain model

**Organization → Plant → Area → Line → Unit → Equipment → Port → Signal**.

Core aggregates (names are provisional):
- `Organization`, `Plant`, `Area`, `ProductionLine`, `LineRevision`, `EngineeringApproval`.
- `EquipmentType`, `EquipmentInstance`, `PhysicalPort`, `MaterialFlow`, `ConnectivityLink`, `TopologyValidation`.
- `Controller`, `ProtocolEndpoint`, `SignalDefinition`, `SignalBinding`, `SignalSample`, `SignalQuality`.
- `AlarmRule`, `AlarmOccurrence`, `AlarmAcknowledgement`, `EventJournal`.
- `Material`, `MaterialLot`, `Recipe`, `RecipeVersion`, `RecipeIngredient`, `ProductionOrder`, `BatchExecution`, `BatchActual`, `BatchRecord`, `QualitySample`.
- `InventoryBalance`, `MaterialMovement`, `DowntimeEvent`, `Shift`, `OperatorAssignment`.

**Separate four graphs/data models**:
1. **Engineering topology:** actual physical equipment and directional, typed ports; equipment capabilities and material-compatibility constraints.
2. **Control model:** controller addresses, tag types, scaling, engineering units, health and value ownership.
3. **HMI scene:** coordinates, graphics, widget presentation, labels and visibility. Layout does not define a physical connection.
4. **Process/recipe model:** procedures, phase/batch operations, recipes, material allocation and tolerances.

Don't mix these graphs in one mutable JSON blob. Version schema plus engineering semantic entities and publish immutable revisions.

## 5. Designer — functional requirements

- **DES-001** Catalog equipment: silo, aggregate bin, water tank, additive tank, scales, feeder, conveyor, screw feeder, mixer, valve, pump, discharge and extensible definitions.
- **DES-002** Drag/drop, pan, zoom, snapping, grouping, copy/paste, multi-select and undo/redo.
- **DES-003** Physical ports each have stable ID, direction (IN/OUT/BIDIRECTIONAL), medium, cardinality, capacity, nominal units and optional connection class; distinguish **water and additives as different physical ports**, even if both are liquid media.
- **DES-004** Typed directed connections validate medium compatibility, permitted directions, fan-in/out, capacity, unreachable equipment and cycles; exceptions only by declared equipment capability.
- **DES-005** Validation differentiates errors blocking publication from warnings and configurable business requirements. Missing required mixer feed ports must block a runnable simulation.
- **DES-006** Edit equipment engineering properties with explicit engineering units; support configurable plant-specific equipment templates.
- **DES-007** Deterministic JSON schema version, import/export, migrations, compatibility tests and non-destructive recovery of invalid drafts.
- **DES-008** Draft / IN_REVIEW / APPROVED / ACTIVE / ARCHIVED lifecycle; activation as a separate authorized action.
- **DES-009** Engineering diff between revisions, source lineage and audit.
- **DES-010** Demo simulation operates on **validated, complete** topology, with deterministic events and no physical actuator command.
- **DES-011** Multi-language UI RU/KZ/EN initially, further language packs later; engineering unit labels independent of locale.
- **DES-012** Reusable canonical equipment registry, with editable per-instance capabilities where authorized.

## 6. Edge + industrial connectivity

- **EDGE-001** Standard OPC UA adapter; subscriptions with reconnect, subscription re-establishment and quality propagation.
- **EDGE-002** Modbus TCP adapter, register maps and scaling, explicit polling budgets, no unbounded polling.
- **EDGE-003** Modbus RTU (phase 2) including serial lifecycle and timeout control.
- **EDGE-004** Signal registry with ID, controller, protocol address, data type, engineering unit, scale/offset, read/write policy, sampling, timestamp and quality.
- **EDGE-005** Readings are **at least once** delivered with source ID and monotonic sequence/dedup keys; Edge has durable store-and-forward for WAN outages.
- **EDGE-006** Reconnect/heartbeat/device offline alarms; receive values independently from central backend availability.
- **EDGE-007** Command mode disabled by default; approved commands use explicit allowlists, server auth, authorization scopes, nonce/idempotency key, expiration, audit and PLC acknowledgement, not raw UI writes to arbitrary tags.
- **EDGE-008** Protocol plugins are isolated from the MES domain; adapter-specific behavior covered by simulator and conformance tests.
- **EDGE-009** MQTT Sparkplug B outbound integration optional for later fleet coordination; not required in R0/R1.
- **EDGE-010** Controller templates must state firmware, options/license, protocol feature support and tested constraints.

## 7. SCADA functional requirements

- **SCA-001** Published engineering scene becomes live HMI; separate EDIT and OPERATE modes.
- **SCA-002** Live telemetry with last value, quality and timestamp; no extrapolated values presented as measured facts.
- **SCA-003** Alarm lifecycle: raise, active, acknowledge, return-to-normal, shelving under policy and historical record; priority and escalation documented.
- **SCA-004** Events/historian with configurable retention and aggregation, fault-tolerant write path and provenance.
- **SCA-005** Time-series trends with intervals and data quality.
- **SCA-006** Connectivity dashboard with protocol health and gateway status.
- **SCA-007** Read-only by default; authorized supervisory command mode only after separate safety qualification.
- **SCA-008** Shift/operator handover journal.
- **SCA-009** Explicit alarm quality rules and no silent suppression of disconnected sensors.
- **SCA-010** Offline on-site operation without reliance on cloud.

## 8. MES for concrete batching

- **MES-001** Raw materials: cement, sand, gravel, water, admixtures, customizable types and lots.
- **MES-002** Recipe versioning, approval and effective dates; target masses/volumes with immutable version per batch. Quantity conversion requires explicit density/standard calibration data.
- **MES-003** ProductionOrder with customer reference, target volume, grade/product, plant, priority and schedule.
- **MES-004** Batch execution record: unique ID, equipment/config revision, recipe version, operator, timestamps, nominal/actual components, deviations and exceptions.
- **MES-005** Quality and tolerance evaluation: configured acceptable difference per ingredient and traceable sign-off. This is not a substitute for laboratory/materials certification.
- **MES-006** Material usage ledger and reconciliation with physical scales and inventory; model measurement uncertainty and calibration state.
- **MES-007** Batch journal and exported production certificate/report; do not assert statutory conformance without local jurisdiction review.
- **MES-008** Shift totals, downtime reason coding, throughput, waste and actual-vs-target variance.
- **MES-009** OEE only where availability/performance/quality denominator definitions are agreed, not as a universal magic score.
- **MES-010** Later: dispatch, truck loading, weighbridge, ERP integration, multi-plant planning.
- **MES-011** Production orders and recipes never directly bypass PLC permission checks.

## 9. Quality attributes & operational constraints

Proposed **laboratory acceptance targets**, subject to measurement and pilot adjustment:
- **NFR-001:** for 1,000 simulated tags at 1s sampling, >=99% end-to-end UI observations have age <=3s in a provisioned local lab without induced network faults (not a real-time safety guarantee).
- **NFR-002:** a 24h simulated uplink outage does not lose acknowledged samples within a documented edge buffer capacity; replay is deduplicated.
- **NFR-003:** no unintended actuator command in read-only mode, even with malicious/invalid UI requests (security/integration test).
- **NFR-004:** every approved recipe revision and activated line revision can be linked to the resulting batch record.
- **NFR-005:** no cross-plant access and no privileged action without server-side policy enforcement.
- **NFR-006:** rollback of app release never implicitly rolls back activated engineering configurations.
- **NFR-007:** visible alarms for stale data and missing heartbeat; source and host clock skew captured for diagnosis.
- **NFR-008:** recovery/runbooks, database backup and restoration tests included in release qualification.
- **NFR-009:** initial supported interface locales RU, KZ, EN; quantity/units locale-neutral internally.
- **NFR-010:** determinism of schema validation and simulation under reproducible fixtures.

Security: TLS and device identity for permitted connections, certificate management, secrets outside source control, signed release artifacts, least privilege, backup encryption and audit. Use network zoning and risk assessment aligned with ISA/IEC 62443 and NIST SP 800-82 Rev.3. Any safety-related claim requires independent hazard analysis and certification as applicable to the site.

## 10. Implementation architecture and stack

**Initial choice: modular monolith**, no premature microservices:
- Frontend: React + TypeScript + React Flow (Designer), typed API, Playwright E2E.
- Core: Java 21, Spring Boot, Spring MVC, JPA/Hibernate, PostgreSQL, Flyway or Liquibase (one migration tool, choose in ADR), Testcontainers.
- Edge: separately deployable Java service, OPC UA via Eclipse Milo, Modbus via supported library adapter, durable local queue (implementation selected after benchmark), explicit protocol interfaces.
- Streaming UI: WebSocket/SSE depending event requirements; do not couple PLC scan loop to browser sessions.
- Historian: PostgreSQL initially, hypertable/time-series extension only after measured retention/query pressure.
- Async messaging: local event/outbox first, MQTT Sparkplug B later; broker/Kafka not a prerequisite.
- Deployment: local Industrial PC on Linux with database and Edge; site backup; optional central fleet backend connected through controlled outbound links.

## 11. Scenarios / acceptance

- **SCN-D01** Engineer constructs a concrete line with correct ports → validation PASS → export/import preserves semantics and stable IDs.
- **SCN-D02** Water and additives connected to distinct mixer inlet ports → both valid; connection to wrong medium fails.
- **SCN-D03** Missing required mixer feed → publication / runnable simulation BLOCKED, diagnostics show exact port.
- **SCN-D04** Attempted cycle in non-recirculating topology → ERROR.
- **SCN-D05** Corrupted local draft → preserved for recovery, not silently destroyed.
- **SCN-E01** Simulated OPC UA disconnect/reconnect → quality transitions STALE/BAD/GOOD, historian keeps provenance.
- **SCN-E02** Outbound WAN outage 24h → persisted queue and deduped replay.
- **SCN-S01** PLC disconnected → UI shows stale, no green live status or implicit write.
- **SCN-S02** User without command permission requests write → 403, zero PLC writes, audit record.
- **SCN-M01** Approved recipe v4 assigned to order → batch immutable reference to v4 despite later v5 release.
- **SCN-M02** Actual ingredient mass exceeds tolerance → deviation and mandatory disposition recorded; no silent acceptance.
- **SCN-X01** Backend process killed mid-batch → PLC remains safe and independently controlled; recovery reconciles batch status from authoritative PLC events.
- **SCN-X02** Attempt from Plant A to read/edit Plant B → denied and logged.

All scenarios need corresponding executable RED tests before implementation. A green unit suite alone is insufficient: integration, security, concurrency, failover and site FAT/SAT are distinct gates.

## 12. Roadmap and gates

- **R0 — Product specification + Designer qualification:** domain, physical ports, topology completeness, JSON migrations, undo/redo, unit and browser tests. No physical PLC access.
- **R1 — Core engineering backend:** PostgreSQL, project revisions, authz, versioned configuration lifecycle, immutable audit, APIs.
- **R2 — SCADA read-only pilot:** Edge OPC UA, Modbus TCP, signal registry, good/stale/bad semantics, historian, live HMI, alarms, offline replay.
- **R3 — MES batching pilot:** recipe versioning, orders, batch journal, material tracking, quality deviations, reports, PLC event reconciliation.
- **R4 — Site-qualified limited supervisory commands:** separate hazard/risk analysis, approved command vocabulary, physical PLC interlocks and independent FAT/SAT; **not an automatic consequence of R3**.
- **R5 — Fleet + vertical packs:** multi-site analytics, additional equipment templates, more protocols, integrations.

**Stage gates**: approved specification and design review → RED tests and independent review → implementation → regression/performance/security → demonstration → on-site engineering acceptance. Public examples never imply certified industrial safety.

## 13. Open decisions (do not silently assume)

- DEC-01: First target PLC make/model, firmware, approved protocol and test environment.
- DEC-02: First pilot concrete plant process (dry/wet, one/two mixers, number of scales) and ingredient tolerance model.
- DEC-03: Agreed responsibility split between owner, plant integrator, PLC engineer and Zavod software vendor.
- DEC-04: Sensor calibration authority, batch measurement units, locale/reporting regulation.
- DEC-05: Edge data buffer implementation, retention and sizing based on pilot telemetry profile.
- DEC-06: Initial availability/latency SLOs for site hardware.
- DEC-07: First release roles, approval chain, remote access policy.
- DEC-08: Rights/licensing for vendor-specific drivers and HMI graphics.
- DEC-09: Whether SCADA functionality is delivered alongside an incumbent HMI or as its replacement following site qualification.
- DEC-10: Separate validation requirements for alarms and supervisory commands.

## 14. Reference framework

- ISA-95 enterprise-control model: https://www.isa.org/standards-and-publications/isa-standards/isa-95-standard
- ISA-88 batch control: https://www.isa.org/standards-and-publications/isa-standards/isa-88-standards
- OPC UA: https://opcfoundation.org/about/opc-technologies/opc-ua/
- Sparkplug: https://sparkplug.eclipse.org/specification/
- ISA/IEC 62443: https://www.isa.org/connectivity-and-cybersecurity/standards
- NIST SP 800-82 Rev.3: https://csrc.nist.gov/pubs/sp/800/82/r3/final

**Review required:** product owner, process/PLC engineer and security/safety reviewer. No operational use of draft configuration without qualification.
