# Доменная модель IndustrialOS — v0.1

**Статус:** Design Draft; целевой контракт, не описание существующей БД.

## 1. Bounded Contexts

- **Engineering**: EquipmentTemplate, EquipmentInstance, PortTemplate, EquipmentPort, MaterialConnection, Line, LineRevision, ValidationFinding.
- **Automation Mapping**: Device, DeviceEndpoint, ProtocolDriver, SignalDefinition, SignalBinding, SignalObservation.
- **Simulation**: SimulationScenario, SimulationRun, VirtualPlantState, SimulationEvent.
- **Manufacturing** (будущее): Material, MaterialLot, RecipeRevision, ProductionOrder, BatchRun, Weighment, Consumption, Alarm.
- **Identity/Organization** (R1): Tenant, Site, Plant, User, Role, Approval, AuditEvent.

Не путать EquipmentTemplate (тип из каталога) и EquipmentInstance (физическая установка на конкретной линии). Типизацией физических портов нельзя подменять PLC-адреса.

## 2. Основная модель

| Сущность | Ключевые поля | Инварианты |
|---|---|---|
| Tenant | id, name, status | данные площадок изолированы |
| Site | id, tenantId, timezone | site принадлежит одному tenant |
| Plant | id, siteId, maxActiveLines, status | технологически нейтрален; первая редакция ограничивает до 10 активных линий |
| Line | id, plantId, name, profileKey, activeRevisionId | профиль процесса определяет валидаторы конкретной линии; активная ревизия опубликована |
| LineRevision | id, lineId, revisionNo, status, schemaVersion, contentHash, createdBy, approvedBy | опубликованная версия неизменяема |
| EquipmentTemplate | templateKey, templateVersion, class, capabilities, defaultPorts, parameterSchema | template version фиксируется в ревизии |
| EquipmentInstance | id, templateKey/version, name, parameters, visualPosition, plantAssetRef? | equipmentId уникален в revision; физический общий актив не дублируется |
| EquipmentPort | id, equipmentId, portKey, direction, connectionKind, media, minLinks, maxLinks, required | порт принадлежит одному оборудованию |
| MaterialConnection | id, sourceEquipmentId/PortId, targetEquipmentId/PortId, medium, properties | соблюдены направления, совместимость, лимиты |
| SignalDefinition | id, deviceId, address, protocolDataType, unit, accessMode | адрес не заменяет семантический signalKey |
| SignalBinding | id, revisionId, equipmentId, signalKey, signalDefinitionId, conversion | bind to target property, immutable by revision |
| ValidationFinding | code, severity, entityRef, messageKey, details | blocking flag объективен и проверяем |
| SimulationRun | id, revisionHash, seed, state, startedAt, finishedAt | воспроизводим, без связи с PLC |
| AuditEvent | id, actor, action, entity, beforeHash, afterHash, timestamp | append-only (R1) |

## 3. Виды графов — нельзя смешивать

1. **Process/material flow graph**: физически перемещаемая среда, направление, порты, агрегаты.
2. **Instrumentation/signal graph**: датчики и команды, теги, device endpoints, semantics.
3. **Layout graph**: координаты и размеры блоков, маршруты визуальных рёбер; не определяют реальную длину труб/ленты.
4. **Procedure graph** (R3): последовательность фаз/операций; отдельно от физической связности.

Изменение визуальной позиции не меняет технологическую валидность; изменение типа или кратности порта — меняет.

## 4. Модель портов

Каждый тип оборудования содержит объявленные порты с:
- portKey и displayName; direction: IN/OUT/BIDIRECTIONAL (для material P0 используются только IN/OUT).
- connectionKind: MATERIAL/UTILITY/SIGNAL/CONTROL (на первом этапе материальные связи проверяются отдельно).
- medium: CEMENT, AGGREGATE, WATER, ADMIXTURE, CONCRETE, OTHER; возможность профилей и иерархии сред.
- minConnections/maxConnections: числовые ограничения, 0..N; required определяется minConnections > 0.
- capacity/flowRate и единицу измерения (после R0.2); allowableRange и допустимые агрегатные свойства — отдельные ограничения.
- порты идентифицируются по equipmentInstanceId + portKey, а не только общему слову liquid.

**Принципиальный пример:** Mixer имеет независимые порты cementIn, aggregatesIn, waterIn, admixtureIn (опциональность химической добавки определяется профилем) и concreteOut. Вода и добавка — разные технологические среды; два потока не занимают один порт.

## 5. Инварианты соединений

- source и target существуют в одной LineRevision и не совпадают по ID.
- OUT → IN; разрешённые классы соединения; medium совместим с обоими портами.
- Для P0 не допускаются параллельные дубликаты (sourcePortId,targetPortId), кроме явно моделируемой раздельной физической линии.
- min/maxConnections для каждого порта соблюдаются при статусе READY; допустима временная неполнота DRAFT.
- Самосвязи и циклы материалопотока запрещены по умолчанию; процессы с recirculation включаются только отдельным типом оборудования/правилом профиля.
- Если компонент питается от нескольких источников, нужен явно поддерживаемый multi-in port или соответствующее устройство; никаких скрытых fan-in.
- Unknown equipment type, schemaVersion или единица измерения при импорте — fail-closed, без подмены.
- Проверка готовности к запуску отдельно от проверки, можно ли сохранить черновик.

## 6. Состояния и ревизии

LineRevision: DRAFT → IN_REVIEW → APPROVED → PUBLISHED → SUPERSEDED; при отклонении из IN_REVIEW → DRAFT, запрет редактирования APPROVED/PUBLISHED. Rollback создаёт новую редакцию на основе предыдущей, а не переписывает опубликованную.

SimulationRun: CREATED → VALIDATING → READY → RUNNING → COMPLETED / FAILED / STOPPED. Старт из статуса READY, только при отсутствии блокирующих ошибок. Реальное оборудование ни при каком статусе SimulationRun не затрагивается.

## 7. Рецептурная модель (поздний этап)

RecipeRevision: productCode, targetOutput, ingredientLines(materialId, quantity, unit, tolerance), mixingTime, qualityConstraints. Decimal arithmetic, фиксирование версии на BatchRun. Нельзя «горячо» менять рецепт выполняемого замеса. Фактические масса/время/качество записываются как отдельные измерения с источником и временем.

## 8. Конкретный путь данных для бетонной линии

AggregateBin → AggregateScale → Conveyor → Mixer.  
CementSilo → CementScale → Mixer.  
WaterTank → WaterDoser → Mixer.  
AdmixtureTank → AdmixtureDoser → Mixer.  
Mixer → Discharge.

Наличие отдельного AdmixtureDoser и точный состав обязательных линий для MVP уточнить в DEC-02. Количество физических портов не должно кодироваться как «один общий liquid».

## 9. Контракт сериализации

Будущий серверный документ LineDefinition schemaVersion=3 **не считать совместимым с JSON v2 прототипа R0.1 до реализации мигратора**. Содержит identity, revision metadata, catalogVersion, equipment[], ports[], materialConnections[], layout, optional signalBindings[]; timestamps ISO 8601 UTC. Перед импортом: parse → version check → schema validate → domain validate → preview → transactional save. Сохранение неизвестных полей: только явно разрешённая extension-область. Никакого silent fallback на демосхему при повреждении пользовательского файла.

## 10. Расширение: 1–10 разнотипных линий и общие ресурсы (R0.3)

**Нормативная корректировка прежнего предположения:** `Plant.plantType` удаляется как единственный обязательный тип производства. `Line.profileKey` выбирается независимо для каждой линии: `batch.concrete.v1`, `continuous.pump.v1`, `discrete.packaging.v1` и т.д. Завод может иметь несколько разнотипных линий, в том числе совместно работающих.

**Plant resource context:**
- `PlantAsset(id, plantId, assetCode, equipmentTypeRef, technicalProperties, activeVersion)` — единый физический объект общего пользования.
- `ResourceAllocation(id, plantAssetId, lineId, allocationKind, capacityConstraint)` — ссылка от линии, а не дублирование агрегата; пересечение эксклюзивных ресурсов — блокирующая ошибка активации.
- `PlantActivationSet(id, plantId, lineRevisionRefs, sharedAssetVersionRefs, state)` — атомарно проверяемая комбинация активных конфигураций. Действующая линия не перезаписывается; изменение общего ресурса требует impact analysis на зависимые линии.
- `Controller` привязан к `Site`; независимые `SignalBinding` связывают сигналы одного/нескольких PLC с оборудованием одной/нескольких линий (N:M).
- `HmiScene` — воспроизводимая проекция утверждённой топологии и каталожных HMI шаблонов; presentation override с независимыми IDs.

**Модельные правила:**
1. `Plant` с 0 линий может быть DRAFT, но не PUBLISHED/ACTIVE; требуется 1–10 активных линий в первой продуктовой редакции.
2. Лимит `maxActiveLines=10` — конфигурируемая продуктовая политика, и валидация на backend обязательна; не фиксировать лимит размером массивов/таблиц.
3. `LineRevision` нельзя активировать в обход `PlantActivationSet`, если она зависит от общего ресурса или другой линии.
4. Межлинейная физическая связь описывается через PlantAsset/интерфейс ресурса; ребро `MaterialConnection` внутри линии не должно напрямую ссылаться на чужую LineRevision.
5. Профильная MES-семантика не навязывается другим процессам: `RecipeRevision` требуется только для batch профилей, если это определено validator.
6. Отдельные ошибки `LINE_LIMIT_EXCEEDED`, `RESOURCE_CONFLICT`, `INCOMPATIBLE_ACTIVE_REVISIONS`, `CROSS_PLANT_REFERENCE` возвращают идентификаторы затронутых объектов.

См. [Universal Industrial Builder R0.3](universal-industrial-builder-R0-3.md) — сценарий инженера, подробный контракт и staged rollout.

