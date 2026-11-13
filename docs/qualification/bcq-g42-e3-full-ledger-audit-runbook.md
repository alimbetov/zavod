# BCQ-G4.2-E.3 — Complete Ledger Audit / Operator Runbook

Date: 2026-11-13
PR: https://github.com/alimbetov/zavod/pull/19
Scope: **engineering evidence only**, no PLC/OT commands or production activation permission.

## Purpose and trade-off

Ordinary idempotent command reconciliation verifies the target and adjoining
hashes for bounded latency; it does NOT detect tampering arbitrarily far back
in history. `BusinessLedgerAuditService.auditAll(...)` explicitly verifies
**every** event and every referenced immutable Blueprint revision for every
Site. It produces site-specific chain-tip anchors for separate trustworthy
retention. `BusinessLedgerAuditProcess` is the non-HTTP, operator-invoked CLI.

It is NOT auto-scheduled in production and has no web endpoint. An operations
owner must configure a separate authorized scheduled invocation (cron, CI/CD,
Kubernetes CronJob or equivalent) with read-only PostgreSQL access. Do not use
the app's unrestricted development DB principal. Do not run from a PLC network
or enable any industrial operations to execute an audit.

## Safe invocation

Compile the project and its test-scope classpath (the CLI intentionally lives
under `src/test/java` so it cannot accidentally expose a production entrypoint):

```bash
mvn -B -DskipTests test-compile dependency:build-classpath \
  -Dmdep.outputFile=target/g42-audit-classpath.txt -DincludeScope=test
CP="target/test-classes:target/classes:$(cat target/g42-audit-classpath.txt)"
export ZAVOD_DB_URL='jdbc:postgresql://AUDIT_DB:5432/zavod'
export ZAVOD_DB_USER='read_only_audit_principal'
export ZAVOD_DB_PASSWORD='<injected securely by the operator>'
export SPRING_JPA_HIBERNATE_DDL_AUTO=validate
java -cp "$CP" kz.zavod.blueprint.BusinessLedgerAuditProcess \
  audit-2026-11-13.json trusted-anchors.json
```

`trusted-anchors.json` is a map by site ID, e.g.:

```json
{
  "example-site-id": {"sequenceNo": 8, "eventHash": "sha256-hex-from-prior-verified-report"}
}
```

First run (bootstrap without a trusted anchor): omit the final argument. Such
a run checks internal consistency only; it **cannot prove that older rows
were not already removed**. A reviewer must authorize initial anchors and
store them outside the DB in access-controlled, tamper-resistant durable
storage. Subsequent runs MUST use previously trusted anchors. For each site
with prior anchor, check the pinned event's ID/sequence hash and that history
was not truncated. The audit checks every site; previously anchored sites
that disappear cause FAIL. It reports current event count and chain-tip hash
as a **candidate for subsequent external anchoring**, not automatically
trusted just because the DB returned it.

Exit status 0 = all Sites verified, exit 3 = at least one failed site (JSON
report still written). Invalid input/IO/startup errors use nonzero exit.
The report path MUST be new; the tool refuses to overwrite existing evidence.
Send failed reports to security/operations, stop related activation workflows
according to site policy, and investigate before trusting new anchors.

## Scheduler and operational controls

- **Recommended pilot frequency:** at least daily; run more frequently for
  high-consequence sites after performance testing. Actual cadence requires
  engineering-owner and OT-safety approval; do not assume CI schedule is a
  commissioned maintenance task.
- Use isolated read-only DB role with SELECT on Site, business_transition,
  blueprint_revision and referenced tables. `ddl-auto=validate` remains
  mandatory, so the audit principal must also be allowed the metadata queries
  required by Hibernate startup.
- Protect report and trusted anchors from the application and its DB role:
  WORM/object-lock store or append-only externally controlled repository.
  If the attacker can rewrite both DB and anchors, hash chaining is not proof.
  The current hashes are NOT HMAC/signatures and do NOT authenticate the actor.
- Alert on any FAIL or missing scheduled audit, preserve diagnostic report,
  correlate events to operator incident and restore from independently verified
  PostgreSQL backups where appropriate.
- Do not replace trusted anchors with a newer candidate until the prior check
  succeeds and an authorized operator approves retention.
- Record timestamp, covered sites, row counts, final hashes, Git commit SHA,
  PostgreSQL snapshot details and report SHA-256 in incident/maintenance record.

## CI acceptance and limitations

`scripts/test-bcq-recovery.sh` now runs the separate CLI after restoring
a fully populated test database, captures JSON evidence, builds a second
off-DB anchor file and runs the CLI again with that supplied anchor. Additional
PostgreSQL tests prove that a forged historical event fails full verification
and that a previously anchored chain cannot silently lose its tail.

This audit does not currently:
- provide a production scheduler or monitoring/alerting integration;
- sign checkpoints or provision actual WORM storage;
- replace independent GitHub reviewer approval;
- qualify real on-prem PostgreSQL access/security or industrial RPO/RTO;
- grant any industrial PLC/OPC UA command permission.

**Release decision:** HOLD PR #19 until independent review, merge gates and
separate industrial safety release approval.
