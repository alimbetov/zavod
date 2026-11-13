# BCQ-G4.2-E.1 — Technical review of PR #19 (engineering integration)

Date: 2026-11-13
Repository: `alimbetov/zavod`
Scope: BCQ-G4 activation/reconciliation, unified transition ledger, PostgreSQL migrations, TF-03 SIMULATED-only monitor, fault-injection, recovery and real-backend tests.
Reviewed implementation baseline: `058e210495071363564e4c2f11837b5ff790b3d1` (4/4 CI PASS).
Review type: **authoring-assistant technical assessment, NOT an independent external reviewer approval**.

## Decision

**HOLD the merge to main pending independent reviewer sign-off and migration/deployment gates.**
This decision concerns whether the PR can be safely integrated as engineering-platform code; it is NOT industrial PLC/OT authorization. The functionality is testable and promising, but evidence necessary for a responsible merge into a shared baseline is incomplete.

## Confirmed evidence

| Area | Evidence | Assessment |
| --- | --- | --- |
| Atomic and replay-safe activation | `ActivationCommandPostgresTest`, Spring transactional service | PASS, isolated PostgreSQL |
| Actual lost COMMIT ACK on Spring DataSource | `ActivationSpringCommitAckLossPostgresTest` intercepts PostgreSQL successful `CommandComplete(COMMIT)`; an independent DB connection verifies site pointer, receipt, activation, legacy audit and unified ledger; replay is unchanged | PASS, deterministic test case |
| Disconnect during COMMIT execution | PostgreSQL deferred-trigger plus independent `pg_terminate_backend` | PASS, **pre-durable** path |
| Restart and backup/restore | `BcqRecoveryProcess`, forced SIGKILL and cold JVM; restored PostgreSQL | PASS, CI fixture |
| PITR | `scripts/test-bcq-g42-pitr.sh`, archived WAL and named target | PASS, isolated CI fixture; not production RPO/RTO |
| SQL least privilege/append-only | `scripts/test-bcq-g42-ledger-privileges.sh`, isolated runtime role and trigger | PASS, synthetic schema |
| TF-03 | `@Profile("dev-local")` controller; only SIMULATED ingress; browser E2E | PASS, local scope; no physical OT integration |
| Latest exact HEAD | `058e210495071363564e4c2f11837b5ff790b3d1` | frontend / verify / recover / real-backend all PASS |
| Independent review | GitHub PR review submissions list | **NONE** |

## Findings, severity and recommended resolution

### R1 — BLOCKER: no independent code/security/migration approval

GitHub PR #19 has no submitted independent reviews. The implementer's own CI, reports and this assessment must not count as external review.

**Required:** named engineer who did not write the change checks the exact eventual HEAD, submits GitHub APPROVE or REQUEST_CHANGES, attaches security and database migration findings. A new commit invalidates any unscoped prior sign-off.

### R2 — BLOCKER for deployed schema: schema upgrade rehearsal not qualified

`scripts/sql/bcq-g4-activation-command.sql` creates receipt and transition tables; `scripts/sql/bcq-g42-business-transition.sql` adds the business ledger. The CI privilege test creates only a minimal `site` table and applies the latter migration on an empty fixture. The recovery suite typically uses Hibernate schema auto-update and later validates it; this is **not** a proof that the reviewed SQL migration sequence will work on a real populated older database with existing Blueprint / approvals / activations and `ddl-auto=validate`.

**Required:** a non-production restored copy of representative pre-upgrade data; apply the complete ordered migrations as the migration owner, verify existing records unchanged, start the application with `spring.jpa.hibernate.ddl-auto=validate` under actual restricted runtime DB role. Test rollback/forward-fix and backup. Never execute destructive migration qualification against production.

### R3 — PERFORMANCE / scalability: full-site hash chain on replay

`ActivationCommandService.verifyCommittedReceipt` calls `BusinessTransitionLedger.verifySite(siteId)`. The latter loads **every** ledger event for the site and re-verifies the associated Blueprint revision for each. Therefore the cost of ordinary idempotency replay and reconciliation grows with *total historical transitions*, potentially delaying a busy site.

**Required:** performance regression at 10K/100K events with p95/p99 and query count, then choose a bounded verification strategy while retaining asynchronous/full-audit integrity sweeps and secure checkpoint anchoring. The chain’s security guarantees must not be silently weakened.

### R4 — SECURITY / provenance: correlation source and absence of user justification

`BusinessTransitionLedger.append` accepts `MDC.get("correlationId")` when present; downstream cryptographic signing of the original identity/correlation context and trust boundary has not been qualified. `reason_code` is an event classification, not evidence that a human supplied a business reason. APPROVE/PUBLISH/ACTIVATE do not require explanatory user reason.

**Required:** verify trusted correlation IDs are generated or normalized at a server-authenticated ingress boundary; document which transitions require explicit reason and how signed actor identity is acquired. Do not claim `reason_code` is user justification.

### R5 — OPERATIONS: industrial RPO/RTO unmeasured

The CI PITR timing reports only startup of a staged restored database. Backup retrieval, operator detection, WAL archive lag, provisioning, application startup, security validation and reconciliation are excluded.

**Required for industrial pilot:** full end-to-end destructive drills under a representative backup architecture with defined RPO/RTO targets, failure modes, evidence and sign-off. Can be explicitly deferred from **engineering-only** merge if deployment hard-gate remains enforced.

### R6 — SAFETY: do not allow OT/PLC commands via this PR

TF-03 controller is `dev-local` profile and simulated sample only; no production PLC command path is qualified. Any production OT adapter, PLC write, OPC UA command, or automatic activation of industrial equipment is **out of scope**. Do not tie engineering-only merge to industrial permit.

## Merge decision criteria (engineering-only)

- [x] Exact HEAD CI for Java/PostgreSQL, recovery/PITR, React and real-backend E2E PASS on reviewed baseline
- [x] Real Spring/Hikari PostgreSQL COMMIT ACK loss verified and replay reconciled exactly once
- [x] TF-03 local-only SIMULATED boundary preserved in changed controller
- [ ] **Independent GitHub reviewer approval** for exact final HEAD
- [ ] **Ordered production-style SQL migration rehearsal** on restored populated pre-upgrade fixture with `ddl-auto=validate` and restricted application DB user
- [ ] Performance risk R3 assigned explicit acceptance/mitigation, with reproducible benchmark evidence
- [ ] Provenance/trust boundary R4 reviewed and accepted for engineering-only scope
- [ ] No enabled production PLC writes; industrial operational gate remains separately blocked
- [ ] Formal reviewer decision: **APPROVE engineering-only merge** or **REQUEST_CHANGES**

Decision today: **HOLD PR #19 in Draft; do not merge to main.**

## Next implementation slice in the same branch

1. Add full SQL migration rehearsal on a populated pre-upgrade PostgreSQL copy, including immutable ledger and app `ddl-auto=validate` as restricted runtime user.
2. Measure ledger replay verification cost under increasing site history; remove unbounded work from the synchronous request or document an acceptable bounded alternative with compensating audit checks.
3. Obtain independent reviewer approval on the final SHA after all corrective commits. Only then make a separate engineering-only merge decision.
