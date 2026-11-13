# Zavod — independent technical review / roadmap R1–R4
Date: 2026-11-13. Scope: PR #12 (CRUD), #13 (RS-0), #14 (RS-1), #15 (site-ownership migration), current `main`, Blueprint lifecycle. This is code/CI evidence-based review; **no production configuration or data were audited**.

## Verdict and verified evidence

**Engineering pilot: conditionally acceptable in dev-local only; enterprise release: HOLD.** PR #13 RS-0 and PR #14 RS-1 remain Draft. Real PostgreSQL 16.6 + Spring Boot + Chromium [run 43420214184](https://github.com/alimbetov/zavod/actions/runs/43420214184) succeeded on RS-1 exact commit `e9a33f63245aec282498ca8885f35c72717bdfbd`; tests include three concrete LINEs, SVG node move, Layout v1→v2, Draft R2→R3, Undo/Redo and reload. RS-0 [run 43420214190](https://github.com/alimbetov/zavod/actions/runs/43420214190) also succeeded. But UI role selection is *not* server authorization. PR #12 CRUD has Java CI green but is not merged. R1 BCQ-G4 remains unimplemented/unqualified in production form.

## P0 findings (release-blocking)

| Finding | Risk | Required change | Oracle |
|---|---|---|---|
| SEC-01 | `dev-local` is only loopback/header security, no OIDC organization-site authorization; role selector is cosmetic | Real OIDC JWT resource server, claims binding, server-side organization/site RBAC+ABAC, negative cross-tenant tests; deny all by default | One tenant cannot see/edit any record, layout, audit, signal, or site of another |
| OI-01 | Missing idempotent lifecycle command protocol and durable command outcome; ambiguous commit result after network loss | BCQ-G4 transactional command journal+unique key, append-only event ledger, PostgreSQL disconnect before/during/after COMMIT tests, outcome query | Exactly one approved transition after concurrent/retried request |
| MIG-01 | `blueprint_site` is application-side ownership authority, but historical approvals/activations/layout may contain contradictory or missing site references; FK migration absent | Signed explicit mapping manifest, read-only audit, quarantined orphans, transactional insert-only backfill, composite FKs as separate migration | All ownership anomalies 0 on production clone and postdeploy audit |
| MIG-02 | PostgreSQL `ddl-auto=update` is the development default; `prod` correctly sets validate, but deployment process/migration sequencing is not yet qualified | Versioned SQL deployment, schema baseline, backup/restore rehearsal, no accidental dev-local profile | Production fails closed on schema drift |
| RS-01 | Separate graph and layout commits can partially succeed; if layout CAS fails after Blueprint save, user must reconcile without duplicate revision | Add an explicit save outcome/repair flow and browser conflict recovery test; avoid claiming atomicity | Cross-tab racing save yields conflict, preserves local edits, no lost graph |
| OT-01 | Operator palette and future HMI have no qualified real signal source; simulating RUNNING as live would mislead | Keep UNKNOWN_NO_TELEMETRY, provenance and freshness independent from lifecycle; read-only Edge gates | Missing binding, offline and stale never render live green |

## P1 findings

| Finding | Change | Verification |
|---|---|---|
| RS-02 | Typed port connection checks exist in frontend but need stronger server-side catalog/type oracles, max-cardinality, duplicate connection, forbidden cross-unit private port, update conflicts | PostgreSQL tests + Chromium negative port connection tests |
| RS-03 | Layout CAS validates equipment IDs in selected unit, not independent frontend view identity; normalize stable IDs, viewport-only dirty state and 99-line performance | Save/load viewport-only, CAS conflict, 99-line lazy-load smoke |
| RS-04 | Node drag Undo/Redo history needs detailed fuzz/regression (one step per drag, canceled drag, deleting boundary equipment), accessibility and keyboard coverage | 100 random moves/reversions and keyboard focus tests |
| ML-01 | `batch.concrete.v1` is structural; no independent physical BATCH material balance/temperature/capacity qualification | Signed golden oracles from a concrete process engineer and fault seeds |
| EA-01 | Engineering Autopilot must not auto-approve, auto-activate or manufacture physical parameters | Explainable deterministic decisions with evidence, preview/diff/explicit apply |
| DEV-01 | Frontend `npm install` without committed package lock weakens transitive dependency reproducibility; supply chain and SCA review required | Commit lock file, `npm ci`, scan, SBOM/provenance |
| UX-01 | UX-99 heuristics are not empirical usability results | 5 interviews, 3 observed role sessions, evidence of lower error/time/cognitive load |

## Recommended roadmap with dependencies

| Sequence | Priority | Gate | Outcome |
|---|---|---|---|
| R1-A BCQ-G4 | P0 | Idempotency, append-only audit, connection loss/unknown outcome, Four-Eyes server identity | Trustworthy business command lifecycle |
| R1-B Site migration | P0 | Production-backup clone, read-only audit, signed owner manifest, no orphans, verified rollback; then FK migration | No cross-site data assignment |
| RS-1 editable E2E | P0 | PR #13 → #14 reviewed in order; real PostgreSQL/Chromium green, port negative tests, conflict recovery | Engineer Save→Close→Reopen without data loss |
| R2 Concrete Golden Model | P1 | Independent expert-approved physical process limits and golden vectors | Physically validated BATCH profile, not just topology |
| R3 Enterprise API/Admin | P1 | OIDC/Org/Site ABAC, audited CRUD PR #12 and migration gates | Controlled multi-company use |
| R4 Engineering Autopilot | P1 | R2 signed rules, Guardian/Impact/preview, explainability and human review | Guided proposals, no unauthorized physical actions |

**Execution strategy:** RS-1 user-visible work may proceed concurrently with R1, but release-qualified approve/publish/activate and all enterprise writes remain blocked by R1-A/B. R4 is only credible after R2 and authorized history. Integration Foundation contracts may continue independently; industrial OT field connection is never implied by a working SVG/HMI preview.

## Backfill qualification link

See `docs/r1-enterprise-ownership-backfill-review.md`, `scripts/sql/r1-site-owner-audit.sql`, `scripts/sql/r1-site-owner-backfill.sql` and `scripts/test-site-owner-backfill.sh`. Synthetic PostgreSQL tests are **not** approval of a production manifest or a production migration.

## Release acceptance

To remove HOLD require: exact merge HEAD CI, branch protection/review evidence, zero P0 findings, 2-person signed backfill and clone rehearsal, real OIDC cross-site tests, BCQ-G4 fault-injection evidence, actual real Chromium/PostgreSQL test after merge, and independent concrete engineer acceptance. No fixed "95/100" can be asserted before these gates and observed customer work.
