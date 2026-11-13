# BCQ-G4.2-E — Final Release Gate / Qualification Record

**Record date:** 2026-11-13  
**Repository:** `alimbetov/zavod`  
**Single working branch:** `feat/bcq-g4-command-reconciliation`  
**Review PR:** [#19](https://github.com/alimbetov/zavod/pull/19)  
**Gate decision:** **HOLD — engineering qualification, not industrial production approval.**

This record separates demonstrated technical test outcomes from unproven deployment
claims. A green automated test suite is necessary, not sufficient, for production approval.

## 1. Acceptance matrix

| Gate | Evidence / execution | Acceptance |
| --- | --- | --- |
| G4.2-A: idempotency, no false rollback, unresolved outcome | `ActivationCommandPostgresTest`; `observe()` distinguishes `COMMITTED` vs `NOT_OBSERVED_RETRY` | Automated functional qualification |
| G4.2-B: atomic audit for five lifecycle transitions | `BusinessTransitionLedgerPostgresTest`; insertion-failure rollback; chain and revision hashes | Automated functional qualification |
| G4.2-C: disconnect before and during COMMIT | Testcontainers `pg_terminate_backend`, deferred COMMIT trigger | Automated fault qualification; does NOT prove post-WAL ACK |
| G4.2-E: COMMIT acknowledged by PostgreSQL but ACK blocked from client | `PostgresCommitAckLossProtocolTest`: real PostgreSQL 16.6, byte-level response proxy intercepts `CommandComplete('COMMIT')`, closes connection without forwarding, independent connection verifies durable state and duplicate-key replay | Requires exact HEAD CI PASS; proxy tests transaction receipt/audit/pointer *model* only |
| Cross-JVM crash and restore | `BcqRecoveryProcess` after JVM SIGKILL, fresh process and database restored from `pg_dump` | Automated recovery qualification |
| PostgreSQL access/immutability | `scripts/test-bcq-g42-ledger-privileges.sh` applies migration to isolated DB, tests runtime least privilege + immutability trigger | Sandbox migration/security qualification |
| PITR | `scripts/test-bcq-g42-pitr.sh`: physical base backup, archived WAL, restore point, later committed row excluded | Sandbox PITR qualification |
| Correlation/reason/provenance | `correlation_id`, `reason_code`, user reason (when provided), snapshot digest and chain hash; tamper tests | Automated content/integrity qualification; actor-supplied reasons only for revoke/restore |
| Actual production identity / site authorization | Production identity provider and representative security deployment | **BLOCKED** |
| Measured industrial RPO/RTO | Representative backup storage/network + complete restore/startup/verification | **BLOCKED** |
| Independent migration/security sign-off | Human reviewer other than implementer, signed dated audit incl. SQL role grants, backfill, activation and rollback strategy | **BLOCKED** |
| OT connectivity / PLC commands | Independent plant/OT safety certification | **OUT OF SCOPE; disabled** |

## 2. What WAL/ACK proxy test does — and does not — establish

The Java test runs a real PostgreSQL 16.6 instance. Its proxy passes the
client's SQL and COMMIT through to PostgreSQL, consumes the server's
`CommandComplete('COMMIT')` response, **does not forward it** and closes the
client socket. The JDBC client receives an error, then a separate PostgreSQL
connection confirms that the state, receipt and audit were committed.
Re-submitting the *same* command key does not create another receipt.

PostgreSQL's successful COMMIT response is evidence that the server completed
COMMIT with the configured synchronous durability guarantees. This is a
**deterministic lost-ACK protocol scenario**, not a physical disk-failure proof.
The test uses three representative transactional tables rather than the
entire Spring lifecycle service; the full Spring service idempotency/atomicity
is covered separately. End-to-end fault qualification through a PostgreSQL-aware
proxy inserted in the actual Spring DataSource remains recommended for pilot sign-off.

## 3. RPO/RTO interpretation

The PITR CI log prints a startup duration from launching the recovery container
*after preparing the physical restore data* until PostgreSQL becomes ready. It
excludes backup generation, WAL shipping, restore copying, verification,
Spring startup, application reconciliation and field operations. It must **not**
be advertised as industrial RTO. Likewise zero missing markers at an explicitly
chosen recovery target is **not** a production RPO target or guarantee.

Before assigning contractual RPO/RTO: run multiple destructive recovery
exercises on representative deployment infrastructure and storage, explicitly
measure WAL archiving lag, recovery and application reconciliation end-to-end,
and publish observed median, p95, worst case and fault taxonomy.

## 4. Independent review checklist — sign-off REQUIRED

- [ ] Reviewer is not implementer; record identity, date, commit SHA and review scope
- [ ] Confirm `COMMIT` ACK-loss test actually observes backend `CommandComplete` and JDBC error
- [ ] Review both commit branches: durable before ACK loss; incomplete COMMIT rollback
- [ ] Confirm no fabricated `COMMITTED` for a missing or inaccessible DB receipt
- [ ] Audit migration prerequisites and deployment order against existing site/approval data
- [ ] Confirm no privilege escalation through runtime SQL role, schema ownership or function grants
- [ ] Verify immutability trigger and independent backup of hash-chain checkpoints
- [ ] Verify authorization, tenant/site isolation and identity provenance in intended production deployment
- [ ] Run `ddl-auto=validate` against the staged migrated DB under the intended runtime DB principal
- [ ] Evaluate performance of full-ledger verification on sites with long histories
- [ ] Publish rollback strategy, operational runbook and security findings
- [ ] Record explicit acceptance or rejection of the pilot scope

## 5. Required evidence before merge to main

1. All required GitHub Actions runs PASS on the **same** latest PR HEAD.
2. Independent reviewer approves code/architecture/SQL and commits a signed-off
   decision referencing the exact SHA.
3. Deployment-specific security, recovery and operations review is complete
   or is explicitly deferred behind an enforceable **production disabled** gate.
4. No production activation or telemetry endpoints are enabled by these changes.
5. Release notes accurately state the scope: **engineering foundation**, not
   commissioned industrial automation.

**Current gate: HOLD.** Do not remove Draft status or merge #19 based solely on
this document or author's own tests. This file is an engineering evidence
index, not an independent audit report.
