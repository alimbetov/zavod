# IndustrialOS — приемочные сценарии, RED-тесты и Quality Gates v0.1

**Статус:** тестовый контракт для review. Сценарии в документе — НЕ свидетельство их выполнения.  
**Порядок:** Scenario Design → RED tests → Independent Test Review → GREEN implementation → Regression → Qualification.

## 1. Матрица сценариев R0.2 (обязательные)

| Scenario | Требование | Given / When / Then | Уровень |
|---|---|---|---|
| SC-LD-001 | FR-DES-001/002 | пустая линия → инженер добавляет Silo и Scale → два объекта с устойчивыми ID на canvas | E2E |
| SC-LD-002 | FR-DES-004/008 | Silo.OUT(CEMENT) → Scale.IN(CEMENT) → валидная связь | Unit |
| SC-LD-003 | FR-DES-008 | Water.OUT(WATER) → CementScale.IN(CEMENT) → BLOCKING incompatible-medium | Unit |
| SC-LD-004 | FR-DES-005 | Mixer.waterIn уже занят; подключаем AdditiveDoser к Mixer.admixtureIn → обе линии существуют независимо | Regression |
| SC-LD-005 | FR-DES-006/008 | порт maxLinks=1 занят → вторая попытка подключения отклонена без изменения графа | Unit |
| SC-LD-006 | FR-DES-008 | Два identical source/target port edges → обнаружен duplicate | Unit |
| SC-LD-007 | FR-DES-008 | MaterialFlow A→B→C→A → blocker unless explicitly declared recirculation profile | Unit |
| SC-LD-008 | FR-DES-008/009 | Mixer требует воду и цемент, но вода отсутствует → validation ERROR, симуляция не стартует | E2E |
| SC-LD-009 | FR-DES-008 | Два equipment instances имеют один ID → BLOCKING duplicate-id | Unit |
| SC-LD-010 | FR-DES-010 | удаление узла с 2 рёбрами и Undo → исходная структура и портовые ссылки восстановлены | E2E |
| SC-LD-011 | FR-DES-011 | повреждённый local draft → предложение восстановления, не молчаливое затирание демосхемой | E2E |
| SC-LD-012 | FR-DES-011/012 | импорт неверного JSON → текущий проект не изменился | E2E |
| SC-LD-013 | FR-DES-012/015 | валидная схема → export/import → одинаковая нормализованная семантика, несмотря на порядок JSON полей | Contract |
| SC-LD-014 | FR-DES-012 | unknown schema version → explicit error, без частичной загрузки | Contract |
| SC-LD-015 | FR-DES-012 | old prototype JSON v1/v2 → поддерживаемый мигратор или явный отказ без потерь; никакой silent conversion | Contract |
| SC-LD-016 | FR-DES-007 | только перемещение блока → доменные порты и соединения неизменны | Unit |
| SC-LD-017 | FR-SIM-001 | симуляция R0 активна → OPC UA/Modbus sockets и драйверы не используются | Security |
| SC-LD-018 | FR-SIM-002 | одинаковые версия/seed/параметры → идентичная последовательность simulation events | Unit |
| SC-LD-019 | FR-SIM-003 | blocking graph error → START disabled + domain start rejected | Unit/E2E |
| SC-LD-020 | FR-DES-013 | попытка недопустимой связи → подсветка обоих портов и конкретный код ошибки | E2E |
| SC-LD-021 | FR-DES-014 | все каталожные шаблоны имеют уникальные keys и валидную портовую декларацию | Unit |
| SC-LD-022 | NFR-01 | 100 узлов + 150 связей на эталонном стенде → p95 UI actions ≤ 300ms | Performance |

## 2. R1 — Backend и права

| Scenario | Given / When / Then |
|---|---|
| SC-BE-001 | ENGINEER создаёт DRAFT; опубликованный результат не изменяется |
| SC-BE-002 | параллельные изменения с одинаковым ETag → одна операция успешна, другая 409/412 |
| SC-BE-003 | пользователь Tenant A пытается открыть линию Tenant B → 403/404 без утечки |
| SC-BE-004 | DRAFT → IN_REVIEW → APPROVED → PUBLISHED только правильными ролями |
| SC-BE-005 | попытка модифицировать PUBLISHED → отказ; rollback создаёт новую версию |
| SC-BE-006 | API получает документ с портовым конфликтом → server-side rejection |
| SC-BE-007 | трансакция публикации падает → audit/activeRevision не расходятся |
| SC-BE-008 | повторная загрузка после сбоя → восстановленный snapshot hash совпадает |
| SC-BE-009 | Testcontainers PostgreSQL: constraints, optimistic locking, isolation, audit |

## 3. R2 — Read-only Edge

| Scenario | Given / When / Then |
|---|---|
| SC-EDGE-001 | OPC UA Simulator publishes tag; Gateway emits correct unit, timestamp, quality |
| SC-EDGE-002 | Device offline → no stale value rendered as GOOD/live |
| SC-EDGE-003 | Reconnect with retry/backoff → no uncontrolled reconnection storm |
| SC-EDGE-004 | Modbus words/endian/scale map tested on known fixtures |
| SC-EDGE-005 | Attempt write via Adapter SPI/API → no write implementation reachable |
| SC-EDGE-006 | loss of backend → bounded queue, no unbounded OOM, no PLC command |
| SC-EDGE-007 | invalid certificate/unknown OPC UA endpoint → connection refused |
| SC-EDGE-008 | duplicate/out-of-order observations → explicit sequence handling |
| SC-EDGE-009 | disconnect network, kill Edge, kill Browser → PLC simulation interlocks unaffected |

## 4. Test pyramid / обязательные CI checks

**R0.2:**
1. npm ci на фиксированном lockfile.
2. npm run typecheck / lint / format check.
3. unit + property-based tests валидаторов/миграции/портов.
4. production build.
5. Playwright smoke: создание схемы, неправильная связь, восстановление, импорт/экспорт, Undo.
6. JSON fixtures golden tests (valid, invalid, legacy).
7. SCA/dependency scanning; no known critical vulnerabilities без документированного исключения.
8. Проверка, что R0 не импортирует production PLC/edge drivers.

**R1:**
JUnit 5 + Spring Boot tests, Testcontainers PostgreSQL, Flyway/Liquibase migration test, REST security/ABAC, optimistic locking, audit integrity, backup/restore, contract tests и API docs check.

**R2:**
Protocol simulators and negative write tests, certificate rejection, malformed payloads, QoS/staleness, disconnect/restart/backpressure tests, penetration review.

## 5. Quality Gates и verdict

| Gate | Условие PASS |
|---|---|
| G0 Specification | все P0 требования имеют измеримый сценарий, владелец определён |
| G1 Test Review | независимый reviewer утверждает RED-контракт и test oracles |
| G2 Implementation | RED → GREEN, все требуемые тесты, линтер, типы и сборка зелёные |
| G3 Integration | импорт/экспорт, deterministic simulation, миграции и recovery проходят |
| G4 OT Safety (R2) | успешные негативные write-тесты, сетевые правила, режим read-only подтверждены |
| G5 Release | review без нерешённых P0/P1 дефектов, зафиксирован SHA и evidence |

Вердикты: PASS, FAIL, BLOCKED, NOT_RUN, NOT_APPLICABLE; слово PASS применять только к фактически выполненной проверке с логами, окружением и commit SHA. На R0.1 ранее упоминались 18 пройденных доменных тестов отдельного архива: **это историческая информация, не CI-результат репозитория Zavod**.

## 6. Evidence template

- Commit SHA / Branch / PR:
- Tool versions, OS, browser, DB/PLC simulator versions:
- Setup and fixtures:
- List of test IDs executed:
- Results PASS/FAIL/BLOCKED and logs:
- Architecture/security review:
- Known defects and severity:
- Recommendation GO / NO-GO:
- Sign-off by reviewer:

Нет автоматического merge при частичных PASS или BLOCKED. Любое изменение бизнес-инварианта требует обновить сценарий и RED-тест до реализации.
