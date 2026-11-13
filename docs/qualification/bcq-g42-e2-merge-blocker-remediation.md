# BCQ-G4.2-E.2 — Merge Blocker Remediation / Qualification

Date: 2026-11-13
Repository: `alimbetov/zavod`
Working branch: `feat/bcq-g4-command-reconciliation`
PR: https://github.com/alimbetov/zavod/pull/19
Verified implementation baseline: `7c7c8c1ddd31e73f839f5794535ac16652d16087`

## Evidence matrix

| Gate | Evidence | Verdict |
|---|---|---|
| Two SQL migrations on populated staged data | `scripts/test-bcq-g42-populated-upgrade.sh` applies `bcq-g4-activation-command.sql` then `bcq-g42-business-transition.sql` on a restored populated PostgreSQL stage with pre-G4 tables deliberately removed | PASS for **emulated pre-upgrade schema**, not a representative production pre-upgrade snapshot |
| Hibernate validate as restricted login | `BcqMigrationVerifyProcess` starts separate JVM with `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`, `g42_stage_runtime` non-owner login; validates preserved Blueprint, Site, Approval and historical ActivationSet; creates new approval/publication/activation and replays key | PASS in isolated stage |
| Ledger immutability | Runtime cannot UPDATE/DELETE/TRUNCATE ledger; trigger independently blocks mutation | PASS for tested role; deployment role verification still required |
| 10K event replay performance | `ActivationReconcileScalePostgresTest`; 50 end-to-end Java service cycles for `observe + reconcile + replay` | p95 **28 ms**, p99 **51 ms** |
| 100K event replay performance | Same test and JVM, 100K hash-linked PostgreSQL business events | p95 **20 ms**, p99 **20 ms** |
| Bounded synchronous receipt verification | `BusinessTransitionLedger.verifyReceiptEvent` checks target plus immediate predecessor/successor, verified event digests and authoritative Blueprint revision bytes | PASS for target/adjacency; NOT full-history attestation |
| Full-chain integrity verification | `BusinessTransitionLedger.verifySite` remains callable for full site audit | Implemented, but **no scheduled production execution/checkpoint attestation** |
| Java/PostgreSQL test suite | https://github.com/alimbetov/zavod/actions/runs/43454706865 | PASS, **74 tests** |
| Recovery + populated upgrade + PITR + ACK protocol | https://github.com/alimbetov/zavod/actions/runs/43454706796 | PASS |
| Frontend | https://github.com/alimbetov/zavod/actions/runs/43454706765 | PASS |
| Real Backend E2E | https://github.com/alimbetov/zavod/actions/runs/43454706506 | PASS |
| Independent PR code/security review | GitHub PR review submissions | **BLOCKED — no independent APPROVE** |

## Exact rehearsal methodology

1. Run `BcqRecoveryProcess seed` against an isolated PostgreSQL database, creating **real populated** Blueprint revisions, approvals, historical activations, site assignments and a lab fixture.
2. Force-stop the seeding JVM and use `pg_dump/pg_restore` to clone the populated database to `zavod_stage`.
3. Drop only newly introduced G4 receipt, legacy audit and G4.2 ledger tables on the staged copy, **emulating** the pre-G4 schema. This is not an original production backup and does not prove compatibility with unknown legacy schema drift.
4. Check original persisted business row counts before and after applying both actual SQL migration files, in order.
5. Create distinct `g42_stage_runtime` PostgreSQL LOGIN, grant application DML on ordinary tables while withholding ledger UPDATE/DELETE/TRUNCATE and all schema ownership/DDL; runtime cannot modify ledger.
6. Reboot Spring application in a separate JVM under runtime credentials and Hibernate `ddl-auto=validate`.
7. Verify preserved site active pointer, 3 legacy activation sets, approved and revoked revisions, Blueprint snapshot. Create a **new** post-migration Blueprint, approve/publish/activate it with an idempotency key; repeat without duplicate and verify the 3-event new ledger chain.

## Performance experiment details

At 10K and 100K a real PostgreSQL site contains hash-linked rows with the same persisted immutable Blueprint revision hash. The benchmark uses 50 cycles of the **actual** Spring business service `observe → reconcile → replay`, in a single CI runner without concurrent load; times are measured in milliseconds. No synthetic mock repository is used. Outliers were not discarded (p95 index 47/50, p99 max of 50).

The original `verifySite` cost was O(total events) per replay. The new hot-path `verifyReceiptEvent` runs a constant number of indexed event lookups and revision checks; `verifySite` remains for full-history audit but **must be invoked separately** to detect tampering in unrelated historical rows. The strict less-than-3-second p99 CI assertion guards against accidental reintroduction of full-ledger synchronous scanning; the benchmark is not a multi-user SLA.

## Remaining blockers

- **R1 independent GitHub review:** no non-author GitHub APPROVE. Must review the final, stable SHA before merge.
- **R2 real historical schema rehearsal:** staging uses actual populated data but deliberately emulates old schema by dropping new tables. Require representative real pre-upgrade snapshot, migration-order approval, and rollback plan before deployment to an established installation.
- **R3 long-tail audit guarantee:** constant-time synchronous checks detect target and adjacent corruption, but NOT arbitrary old-row tampering until separately executed full audit. Schedule/report secure checkpointed offline verification and define monitoring/response before elevated trust claims.
- **R4 authenticated request provenance:** MDC `correlationId` is not externally attested; `reasonCode` is a transition classification rather than human justification.
- **R5 industrial deployment:** production identity, complete RPO/RTO, OT separation and PLC qualification remain unapproved.

**Merge decision: HOLD.** Do not self-approve PR #19. Do not enable production PLC or OT writes. A *separate engineering-platform* merge becomes eligible only after independent review and explicit acceptance of all non-industrial residual risks.
