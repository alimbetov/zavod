# Архитектура Zavod / IndustrialOS — R0 → R3

**Статус:** Design Draft; границы промышленных зон обязательны.

## 1. Слои системы

~~~text
[Engineering Web UI — React + TypeScript, React Flow]
        | HTTPS / REST (R1) / WebSocket read model (R2)
[Plant Management Backend — Java 21 + Spring Boot]
        | PostgreSQL (versioned configs, audit, recipes, operations)
        | internal auth + policy / outbox if required
[Industrial Edge Gateway — отдельный сервис на plant LAN]
        | OPC UA client READ-ONLY (R2) / Modbus TCP reader (R2)
        | bounded buffering, normalized telemetry, health
[PLC & local HMI / Safety PLC — отдельные trust/safety boundaries]
        | fieldbus / local IO (PROFINET, EtherNet/IP, Modbus, etc.)
[Sensors, scales, valves, motors, mixer, emergency circuits]

[Optional Site → Central analytics] only outbound authenticated,
never necessary for local equipment safety or machine operation.
~~~

Архитектура поддерживает один site on-premise первым; multi-tenant и fleet management — целевая модель, не техническое оправдание для преждевременного усложнения R0.

## 2. Граница ответственности

| Уровень | Выполняет | Не выполняет |
|---|---|---|
| React Editor | чертёж, настройки, ошибки, симуляция | циклическое управление PLC |
| Spring Boot | версии схем, учёт, права, API, audit | interlocks, real-time control |
| Edge Gateway | чтение тегов, нормализация, качество, очередь | safety-critical logic |
| PLC | последовательности и взаимодействие с I/O в локальном контуре | зависимость от облака |
| Safety circuits | аварийный останов / защитные отключения | управление через веб-запрос |

При остановке Backend/Cloud/Browser PLC и защитные контуры должны сохранять безопасную независимую работоспособность согласно проекту OEM и site-specific HAZOP/risk assessment.

## 3. Технологический стек (выбор для реализации)

- Frontend: React, TypeScript, Vite, React Flow; тесты Vitest + React Testing Library + Playwright.
- Backend: Java 21, Spring Boot MVC, Bean Validation, Spring Security, Spring Data JPA, PostgreSQL; Testcontainers.
- Миграции БД: контролируемые, инструмент определяется DEC-06.
- Edge: отдельный Java процесс/контейнер на Industrial PC, plugin-like protocol adapter SPI; Eclipse Milo как кандидат OPC UA.
- Telemetry: в R2 локальный PostgreSQL для начала; TimescaleDB/MQTT Sparkplug B — только при доказанной необходимости и после baseline нагрузки.
- Integration: OPC UA с серверными сертификатами и allowlist; Modbus TCP только в изолированной OT-сети и строго READ-ONLY.
- Observability: structured logs, Micrometer/Prometheus (по необходимости), correlation IDs, health/readiness.
- Deployment: локальная площадка может работать без Интернета; внешняя аналитика факультативна.

## 4. Предлагаемые границы модулей

frontend/line-designer — canvas, palette, inspector, validation panel, history.  
shared/line-schema — versioned JSON schema, types, migration fixtures.  
backend/engineering — lines, revisions, catalog, approvals, audit.  
backend/production — orders, recipes, batches (позже).  
edge/adapter-core — SignalObservation, adapter lifecycle, subscriptions, quality.  
edge/adapter-opcua; edge/adapter-modbus; edge/adapter-simulator — отдельные реализации.  
testing/fixtures — valid/invalid line graphs, simulated devices.

В R0 допускается монолит фронтенда с изолированным domain; дробление на микросервисы до фактической необходимости запрещено архитектурным решением.

## 5. API-контракт (целевые примеры, R1)

| Метод | Endpoint | Семантика |
|---|---|---|
| GET | /api/v1/sites/{siteId}/plants | доступные установки |
| POST | /api/v1/plants/{plantId}/lines | создание Line |
| GET | /api/v1/lines/{lineId}/revisions | список редакций |
| POST | /api/v1/lines/{lineId}/revisions | новый DRAFT |
| PUT | /api/v1/revisions/{revId} | запись при If-Match, optimistic lock |
| POST | /api/v1/revisions/{revId}/validate | общий server-side validator |
| POST | /api/v1/revisions/{revId}/submit | перевод в review |
| POST | /api/v1/revisions/{revId}/approve | утверждение по роли |
| GET | /api/v1/catalog/equipment | библиотека шаблонов |
| GET | /api/v1/revisions/{revId}/audit | записи изменений |

Ошибки — Problem Details, стабильный errorCode, binding к entity/port, никакого leak endpoint credentials.

## 6. Edge contract (R2)

SignalObservation = {siteId, deviceId, signalKey, value, unit, sourceTimestamp, receivedAt, quality, sequence, provenance}. В R2 доступен только outbound read. Gateway соблюдает bounded queue, reconnect с backoff/jitter, refresh certificate lifecycle, distinct connected/disconnected/stale. Отсутствие данных нельзя интерпретировать как ноль. Write endpoints отсутствуют.

## 7. Данные и целостность

- Конфигурация линии хранится как versioned JSONB snapshot + реляционная индексация основных сущностей (детали по профилированию).
- Иммутабельные опубликованные версии, транзакционный audit, optimistic locking.
- Клиентское localStorage R0 — лишь черновик, не архив и не single source of truth после R1.
- Резервное копирование/восстановление должно быть протестировано прежде производственного применения.
- Время UTC, local TZ как атрибут Site, числа для массы/объёма в decimal с явной unit conversion.

## 8. Переходы и архитектурные ограничения

R0.2: никаких реальных PLC драйверов и Backend.  
R1: серверное хранение/права/ревизии, но нет PLC-control.  
R2: реальная телеметрия только read-only, site network isolation.  
R3: технологический учёт, а машинные команды — отдельный safety-gated workstream и без обещания готовности на этапе проектирования.

Стандарты для проектирования границ: ISA-88, ISA-95, ISA/IEC 62443 и NIST SP 800-82 Rev.3; соответствие стандартам требует отдельного аудита.
