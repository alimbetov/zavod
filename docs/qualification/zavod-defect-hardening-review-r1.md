# ZAVOD — Technical Defect Review and Unit/Integration Test Hardening (R1)

Date: 2026-11-13
Repository: https://github.com/alimbetov/zavod
Working branch: `feat/bcq-g4-command-reconciliation`
Review scope: Java 21 / Spring Boot 3.5 / JPA / PostgreSQL 16.6; G4 transactional lifecycle and immutable business ledger; TF-01/02/03 telemetry; DEV-local React engineering walkthrough; production safety boundary.
Review method: direct code inspection, targeted RED tests followed by GREEN fixes, Testcontainers PostgreSQL, deterministic concurrency, pure JUnit, browser E2E, JaCoCo measured reports.
**This is an assistant-authored technical review, NOT independent GitHub reviewer approval.**

## 1. Confirmed defects and the corresponding executable oracles

| Finding | Severity | Source defect | Remediation | Evidence/test |
|---|---|---|---|---|
| H-01 | P0 | `BusinessLedgerAuditService.auditAll` verified the historical hash chain in a read-only transaction, then fetched the tip from a DIFFERENT read committed transaction. An unverified concurrent append could become the accepted audit checkpoint. | Per-site `TransactionTemplate`, `REQUIRES_NEW`, `REPEATABLE_READ`, read-only. All verification, tip selection and anchor comparison share one PostgreSQL snapshot; site failures are isolated. | `BusinessLedgerAuditSnapshotPostgresTest.concurrentCommitDoesNotBecomeUnverifiedChainTip`; `BusinessTransitionLedgerPostgresTest.corruptedSiteMustNotHideHealthySiteAuditEvidence` |
| H-02 | P1 | `TelemetryIngestionService.history` used `ORDER BY source_time ASC LIMIT ?` and returned the oldest readings when a window contained more than N. New alarms could disappear from history charts. | PostgreSQL newest-N DESC query, then chronological ASC presentation. | `TelemetryIngestionPostgresTest.boundedHistoryReturnsMostRecentSamplesInAscendingTimeline` with 115 persisted samples |
| H-03 | P0 | `TelemetryIngestionService.latest` filtered future source timestamps before passing data to `TelemetryQualityEvaluator`. An accepted newer future sample was ignored while a prior GOOD sample was shown as CURRENT. | Fetch actual latest sample without SQL future-time predicate, then apply server fail-closed quality evaluation; the previous GOOD cannot mask an UNTRUSTED newest sample. | `TelemetryIngestionPostgresTest.acceptedFutureTimestampCannotBeHiddenByPreviousGoodReading`; `TelemetryMonitorDevApiPostgresTest` negative HTTP assertion |
| H-04 | P1 | `PlantDemoDevController.demoSignal` matched only signal key + unit and could reuse a binding for a different equipment, engineering unit, or non-simulated origin. | Check equipment identity, signal source `SIMULATED` and `bar` unit; fail closed on wrong mapping. | `PlantDemoDevApiPostgresTest.demoMustNotReuseSignalOfUnexpectedEquipment` |
| H-05 | P1 | Two concurrent DEV simulated samples could attempt to register the same logical signal and fail with an unhandled DB uniqueness exception. | Recover only an independently committed winner mapping after a typed uniqueness collision; recheck identity and source. | `PlantDemoDevApiPostgresTest.concurrentDemoSamplesUseOneSignalMappingWithoutLostObservations` |
| H-06 | P0 | `SignalRegistryService.registerSimulated` used JPA `saveAndFlush` with caller-selected UUID and no version field. A repeated UUID could trigger an UPDATE/merge, silently retargeting persisted historical samples. | Insert-only prepared SQL `INSERT ... ON CONFLICT DO NOTHING`; identical replay returns the same definition; different data with reused UUID or natural key raises `TelemetryIngestionConflict`. | `TelemetryRegistryPostgresTest.registeredSignalIdentityMustNeverBeRetargetedWithSameUuid` |
| H-07 | P1 | External ledger anchor admitted negative sequence/malformed hash due to absent record constructor validation. | Fail-fast `Anchor` validation, lowercase 64-character hash for positive sequence, empty hash only for genesis. | Pure `BusinessLedgerAnchorTest` |
| H-08 | P2 | Telemetry reused-event payload conflict returned generic `REVISION_CONFLICT` HTTP 409, confusing clients and diagnostics. | Specific `TELEMETRY_EVENT_CONFLICT` code, still HTTP 409. | `TelemetryMonitorDevApiPostgresTest` conflicting same-key payload assertion |
| H-09 | P2 | Positive sub-millisecond `staleAfter` rounded to zero when stored in integer milliseconds. | Validate minimum one millisecond before persistence. | Pure `TelemetryQualityEvaluatorTest.signalExpiryMustBeRepresentableInPersistedMilliseconds` |
| H-10 | Test gap | Pure telemetry quality classification and command actor-bound idempotency had insufficient explicit boundary/security oracles. | Pure table-driven-style tests for future, BAD/UNCERTAIN/STALE/DISCONNECTED, exact staleness edge, unit conversion, mismatched source; PostgreSQL tests for actor-specific idempotency conflict, malformed keys, and no duplicate transitions. | `TelemetryQualityEvaluatorTest`, `ActivationCommandPostgresTest.actorBoundReceiptRejectsAdminImpersonationAndBadRequestKeys` |

## 2. Coverage baseline and remaining risk

**Measured intermediate SHA** `8914f3ea8724fca71f2f7d5007e59a209d1015e8` (the latest code changes should be measured again before final merge):

- Maven JUnit **89 PASS**;
- JaCoCo **87.6% line**, **61.2% branch** overall;
- TelemetryQualityEvaluator **100.0% branch** (22/22);
- SignalRegistryService **100.0% branch** (4/4) before the insert-only change;
- TelemetryIngestionService **63.2% branch**;
- BusinessLedgerAuditService **65.0% branch**;
- ActivationCommandService **60.7% branch**;
- BusinessTransitionLedger **53.3% branch**;
- PlantDemoDevController **57.4% branch**;
- SiteSecurityConfiguration **73.3% branch**.

`scripts/report-coverage.py` was extended to print coverage for these exact critical classes. **Counting successful tests is not a substitute for measuring branches or fault-path oracles.** There is no broad, enforced 80% branch coverage gate at present. Avoid setting arbitrary global thresholds before formally classifying legacy exemptions and measuring the final baseline.

## 3. Remaining technical weaknesses (not fixed or not qualified by this R1)

### Q-01 — Large history full-audit memory and runtime (P1)

`BusinessTransitionLedger.verifySite` currently calls `findBySiteIdOrderBySequenceNoAsc`, materializing every event and reloading verified Blueprint revision data for each row. The 10K/100K previous benchmark measured **bounded reconciliation**, not full-site audit heap, timing, and PostgreSQL query count.

**Test-first qualification:** 10K, 100K, 1M rows; memory/RSS, audit elapsed time, database queries, snapshot consistency, and chain corruption across batch boundaries. Implement cursor/keyset paging or a bounded row projection and a protected checkpoint procedure only after the RED test. Guard against missed/duplicated records during concurrent append.

### Q-02 — Full audit operations/security (P1)

The CLI has no commissioned scheduler, alerting or WORM/signed checkpoint storage in the target plant infrastructure. A hash chain in the same DBA trust domain does not prove historical non-tampering. Independent protected anchors are required. Tests need deliberate anchor replacement, lost audit schedule, storage tamper, and recovery drills.

### Q-03 — Business actor provenance and identity (P1)

`BusinessTransitionLedger` uses MDC correlation ID when present, but the authenticated ingress trust boundary and cryptographic actor attribution are not qualified. DEV demo roles are expressly synthetic. Require trusted real identity provider, authorization/ABAC, explicit business reason where required, and tamper-proof audit retention before customer production use. Add security tests for wrong site, forged principal, role mismatch, same-person four-eyes violation.

### Q-04 — Activation and WAL/ACK fault matrix (P1)

Current full Spring COMMIT-ACK drop is deterministic but exercises one loss timing and one topology. Additional negative qualifications: failover, in-flight commit retry convergence, partial network partition, pool starvation, concurrent command keys, timeout boundaries, and DB read replicas. Separate engineering integration from physical OT approval.

### Q-05 — DEV-local demonstration is not a production safety control (P0 deployment guard)

`PlantDemoDevController` creates synthetic roles inside the local database. `@Profile("dev-local")`, loopback and DEV write header are its only intended access boundary. No industrial authorization, PLC/OPC UA write, certified pressure alarm, or automated physical actuation is implemented or permitted. Real user auth and safe externally isolated staging must be independently designed.

### Q-06 — Test process hardening (P1)

- Introduce a **targeted** unit/branch coverage baseline gate for risk classes after the final report.
- Add mutation testing to pure contracts (telemetry quality, engineering units, permission rules, hash/anchor validation); require meaningful mutation score rather than raw coverage alone.
- Add PostgreSQL concurrency and security negative tests to the existing CI workflows without promoting mocked success as real evidence.
- Avoid flaky timing-only tests; use barriers and source-controlled test clocks for races and timestamp boundaries.
- Split independent, per-scope test suites from expensive process recovery, so PR failures point to the right business invariant.

## 4. Merge and release decision

- Keep existing **one working branch** and PR #19 **Draft/HOLD**.
- Confirm all four CI jobs PASS for final **exact SHA** and inspect detailed test results and JaCoCo report.
- Seek an independent human GitHub reviewer approval after final changes. Assistant-authored audits are not independent reviews.
- Engineering-only merge must remain a separate explicit decision.
- `main` untouched; Telemetry SIMULATED; industrial PLC/OT writes remain BLOCKED.

The next appropriate effort after R1 is **Q-01 streaming/bounded full-audit verification plus a branch-coverage/mutation-testing gate**, not expanding customer-facing functionality.
