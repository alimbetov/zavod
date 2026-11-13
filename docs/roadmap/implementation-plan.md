# Zavod / IndustrialOS — реализационный план и контрольные рубежи

**Статус:** Proposed sequencing, не календарный график и не отчёт о завершённой реализации.

## Current baseline

- GitHub repo Zavod был пустым до создания документации 2026-11-13.
- В предыдущем обсуждении был подготовлен отдельный архив Line Designer R0.1 (React/TS), с 11 шаблонами и 18 доменными тестами. Он **не импортирован в Zavod**; любые утверждения о его работоспособности внутри данного repo требуют повторной проверки.
- Текущая задача PR: согласовать ТЗ, модель, safety-boundaries и тестовый план.

## R0.1-IMPORT — принять исходный прототип в репозиторий

Содержимое: импорт исходников отдельным feature PR, pin Node/npm versions, package-lock, build scripts, CI, тестовые evidence. Сопоставление JSON v1/v2, выявление и фиксация расхождений после ревью. Нельзя смешивать импорт с изменением доменной семантики, пока не получены baseline CI results.

Exit criteria: чистая сборка и все зафиксированные unit/e2e smoke, воспроизводимый запуск на macOS/Linux, отчёт о дефектах.

## R0.2 — Engineering Line Designer [P0]

Этапы:
1. Scenario Design с 22 SC-LD и инженером-рецензентом.
2. RED-тесты портов, обязательных входов, материала и безопасности симулятора.
3. Версионированный каталог оборудования: типов, параметров, портов и единиц.
4. Инженерная валидация графа; отдельная проверка READY.
5. UI: подключение к конкретным портам, ошибки, Undo/Redo, импорт preview/recovery.
6. Детерминированная симуляция и журнал шагов (не физическое управление).
7. CI + скриншотные E2E, документация миграций.

Exit criteria: Gate G0–G3, нет P0-блокеров; демонстрационная схема бетонного завода может независимо подключить цемент, инертные, воду и химическую добавку при соответствующем профиле.

## R1.0 — Java foundation & versioned engineering storage [P1]

- Spring Boot MVC + Java 21, PostgreSQL, управляемые миграции.
- Tenant/Site/Plant/Line/Revision, API, RBAC, append-only audit.
- Server-side validation, optimistic locking, approvals, immutable publication.
- Contract and Testcontainers integration tests.
- Frontend подключается к Backend вместо единственного localStorage, поддерживает offline draft/конфликты.
- Не содержит доступа к PLC.

Exit criteria: Gate G0–G3 + SC-BE-001…009, квалификация backup/restore.

## R2.0 — Simulator Gateway and OPC UA read-only [P1]

- Adapter SPI, OPC UA simulator, воспроизводимый test harness.
- Gateway read-only, quality/sourceTimestamp/staleness, сертификаты и discovery policy.
- Site-level deployment and network ACL, disconnect tests.
- Read-only подключение к первому реальному контроллеру **только после approval**.

Exit criteria: Gate G4, SC-EDGE-001…003/005…009, протокол испытаний без write.

## R2.1 — Modbus TCP read-only and multi-device inventory [P1]

- Register map, byte/word ordering, scaling, unit tests.
- Ошибки датчиков, диагностика с качеством и журнал событий.
- Нет команд в PLC; MQTT Sparkplug B в P2, только если обоснована необходимость.

Exit criteria: SC-EDGE-004, повторная security/OT проверка.

## R3.0 — Batch operations / concrete plant [P2]

- Catalog материалов, версионированные рецепты, заказы на производство, фактические замесы и сменные отчёты.
- ISA-88-подобное разделение recipe/equipment/procedure; ISA-95 уровни интеграции.
- Read-only reconciliation факта из PLC допустимо раньше, чем write.
- Write-capable контур управления — **другой workstream** с HAZOP/FAT/SAT и отдельным GO/NO-GO.

Exit criteria: трассируемость recipe revision → batch → измерения, неизменяемые записи, инженерная приёмка.

## R4+ — Multi-site analytics [P2 / не в текущем ТЗ MVP]

- Fleet dashboard, SLA и аналитика, атрибуция downtime.
- Опциональный MQTT Sparkplug B/TimeSeries storage.
- ML для прогнозов — только после накопления качественной телеметрии и измерения экономического эффекта.

## Sequencing and dependency rules

R0.1-IMPORT → R0.2 → R1.0 → R2.0 → R2.1 → R3.0 → R4+.  
Запрещены: direct PLC write до приёмки, публикация mutable revision, смешивание UI и технологической/safety логики, автоматический merge без evidence.

## Первая серия задач

- Issue A: аудит и импорт R0.1 + CI baseline.
- Issue B: Scenario Design + RED tests портов и графа.
- Issue C: domain/catalog schemaVersion 3 + import/migration policy.
- Issue D: React Flow порты, undo/redo и ошибки.
- Issue E: deterministic simulator с изолированным VirtualPLC.
- Issue F: независимое R0.2 qualification review.

Все номера Issue и PR назначаются GitHub после создания; документ не притворяется уже созданными задачами.
