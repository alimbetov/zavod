# Zavod React Studio — RS-0 Workspace Shell

Status: local-development engineering workspace. **Not production UI and not an OT control surface.**

## Contents

- One React 18 / TypeScript / React Flow application under `web/`.
- Three view-only personas: ENGINEER, OPERATOR, REVIEWER. They select presentation, **not server authorization**.
- Three layouts: FOCUS / SPLIT / OVERVIEW.
- Eleven thematic lenses. Combination IDs match `docs/design/ux-99-variants.json` (99 total).
- Fixed, audited inline SVG equipment symbols and a three-line concrete BATCH design.
- Site-owned, local-only backend bridge to create Site + Blueprint, fetch guided concrete starter, validate and save a DRAFT revision with expectedRevision CAS.
- Equipment selection and Engineering X-Ray. Current status is always `UNKNOWN_NO_TELEMETRY`; no invented live events or PLC writes.

## Run local development

Requires PostgreSQL, Docker where Testcontainers tests run, Java 21 / Maven and Node.js 22.

1. Start the existing Java backend with the deliberately restricted profile `dev-local` and local PostgreSQL.
2. In a second terminal, run:

   ```bash
   cd web
   npm install
   npm run dev
   ```

3. Open `http://127.0.0.1:5173`.
4. Select **Создать локальный стенд**, then **Бетонный завод** (3 interdependent lines).
5. Select a line; select a device to inspect its ports. Switch roles, layouts and thematic lenses.
6. Press **Проверить** and **Сохранить Draft**; then **Перезагрузить** to verify persisted Blueprint.

Frontend and backend are both bound to localhost. Vite forwards `/api` to `127.0.0.1:8080` for local development. Do not expose `dev-local` to a network and do not rely on the `X-Zavod-Dev-Write` header for security. The frontend does not perform production identity or role authorization.

## Verification

```bash
cd web
npm test
npm run build
```

GitHub Actions `react-studio-rs0` runs the same checks. Java `blueprint-r05` and `bcq-g3-process-recovery` separately qualify the backend test slice. **A successful build is not browser E2E**; Playwright with a real PostgreSQL backend, graph/layout round-trip, concurrent editor conflicts and keyboard/accessibility checks remain open.

## Next slices / known limitations

- RS-1: actual editable equipment/typed port canvas, node move with independent ViewLayout CAS, explicit undo/redo, verified Save/Close/Reopen in Chromium.
- RS-2: Guardian deep link/fix proposal with explicit approval, full X-Ray at equipment granularity and semantic diff.
- RS-3: Change Time Machine requires authenticated actor/reason, BCQ-G4 idempotency and append-only audit before any trusted lifecycle write.
- RS-4: HMI read-only UNKNOWN state and only then qualified `SIMULATED` telemetry. Real physical signal data need OT gates.
- 99 design combinations are an experiment catalog, not 99 finished visual designs or measured cognitive-load improvements.
