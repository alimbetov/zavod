# Zavod — Physical Process OS

**Продукт:** универсальная платформа моделирования физических процессов. **Zavod Blueprint** — единая цифровая инженерная схема объекта, из которой в следующих этапах будут строиться симуляция, HMI/SCADA, MES и производственный учёт.

**Единственное нормативное ТЗ:** [docs/ZAVOD-MASTER-SPEC.md](docs/ZAVOD-MASTER-SPEC.md). Не создавать конкурирующих документов требований.

## Реализовано в репозитории

- Java 21 Smart Equipment Library с версионированными шаблонами, типизированными портами и каталогом оборудования.
- Лабораторная генерация/эмуляция 1, 10, 50, 99 связанных разнотипных производственных линий; это **не физический Digital Twin**.
- Универсальный `ProcessBlueprint` для LINE / CIRCUIT / ZONE / STATION, структурная проверка и диагностика.
- `BlueprintDiff` и `BlueprintImpactAnalyzer` без квадратичного materialization общего ресурса; `ResourcePolicyValidator`.
- JPA/PostgreSQL append-only Blueprint revisions, атомарный BlueprintHead CAS, controlled revision conflicts, SHA-256 snapshot integrity.
- Java/JUnit/Testcontainers smoke и интеграционные тесты, GitHub Actions на `main`.
- Серверное утверждение и публикация конкретной ревизии, выбор активных модулей, сохранение исторических `PlantActivationSet`, атомарное обновление активного указателя площадки и проверка прав на основе authenticated principal + SiteMember.
- **R0.6A Engineering Properties:** физические единицы и dimension checks, паспортные значения/диапазоны/evidence и сохранение свойств оборудования в versioned Blueprint JSON (`blueprint.v2` с чтением v1).
- **R0.6A Studio Backend Bridge (только `dev-local`):** версионированный Equipment Catalog, создание/чтение/сохранение Draft, проверка схемы, Impact Analysis, независимый JPA ViewLayout с optimistic conflict. Полноценный производственный HTTP API и внешняя аутентификация всё ещё НЕ реализованы.

## Ещё НЕ реализовано / НЕ квалифицировано

- Визуальный React Blueprint Studio: standalone-прототип пока не перенесён.
- Подтверждённая эксплуатационная квалификация процесса утверждения (внешний identity provider, доверенное администрирование членов площадки, полноценный аудит и disaster recovery).

- Проверка полного каталога и физического процесса для всех 99 синтетических линий; реальный line capacity / material balance.
- 99 физических PLC, промышленная SCADA/MES, real hardware/OT FAT/SAT, HMI и управление реальным оборудованием.

**Технологии:** Java 21 / Spring Boot / JPA / PostgreSQL; React + TypeScript + React Flow (после квалификации ядра); бесплатный OSS (Apache-2.0 предпочтительно, MIT и другие совместимые permissive лицензии допустимы с уведомлениями). Vaadin Flow не используется. Liquibase/Flyway пока нет. `spring.jpa.hibernate.ddl-auto=update` только для DEV; в PROD `validate` и отдельная квалификация схемы.

## Паспорт инженерного ядра и тестовое покрытие

Zavod Core — **Java/JPA инженерная модель**, не SCADA/PLC: versioned Catalog → ProcessBlueprint → Validation/Impact → PostgreSQL Draft CAS → Approve/Publish → PlantActivationSet. `Activate` выбирает инженерную конфигурацию и **не запускает физическое оборудование**. Полное описание агрегатов, границ и тестовой матрицы: [§29 единого мастер-ТЗ](docs/ZAVOD-MASTER-SPEC.md).

**JaCoCo на верифицированном commit `14c66394`: 84,5% строк (1 078/1 276), 58,3% ветвей (575/986)**; это покрытие production-классов смешанными pure-domain и JPA/PostgreSQL JUnit тестами, а НЕ доля выполненных бизнес-инвариантов. `mvn verify` запускает 25 JUnit методов (включая 5 обёрток уже существующих smoke-тестов), плюс отдельные каталожные и 10 000 детерминированных графовых проверок. [JaCoCo/CI](https://github.com/alimbetov/zavod/actions/runs/43400594412).

**BCQ-G3 пройден частично:** реальный Java `SIGKILL` после commit, повторное чтение 99 линий с 853 устройствами другой JVM, `pg_dump → pg_restore` в чистую БД и независимая повторная проверка — PASS. [CI recovery](https://github.com/alimbetov/zavod/actions/runs/43400594111). PostgreSQL CHECK fault подтверждает атомарный rollback; отдельный RED→GREEN тест закрывает некорректный cross-site ActivationSet pointer. **Всё ещё OPEN:** обрыв DB/kill во время commit, idempotency keys, audit/outbox, backup RPO/RTO, внешняя identity и разделение полномочий.

Локальный запуск:
```bash
bash scripts/test-blueprint-core.sh    # Java 21, pure domain + 10 000 seeded graphs
mvn verify                              # PostgreSQL Docker/Testcontainers + JaCoCo
bash scripts/test-bcq-recovery.sh        # изолированная PostgreSQL + SIGKILL + dump/restore
```
Последняя команда создаёт и удаляет собственный Docker-контейнер PostgreSQL на `127.0.0.1:55432`. Не запускайте тесты со значениями переменных реальной production-БД.

## Локальный Backend API R0.6A (только разработка)

По умолчанию HTTP API **закрыт**. Для локального стенда с PostgreSQL и Java 21 явно включите профиль `dev-local`, который слушает только `127.0.0.1`:

```bash
export ZAVOD_DB_URL='jdbc:postgresql://127.0.0.1:5432/zavod'
export ZAVOD_DB_USER='zavod'
export ZAVOD_DB_PASSWORD='<set-locally-not-in-git>'
mvn spring-boot:run -Dspring-boot.run.profiles=dev-local
```

В другом терминале:

```bash
curl -X POST http://127.0.0.1:8080/api/v1/dev/sites \
  -H 'Content-Type: application/json' \
  -H 'X-Zavod-Dev-Write: true' \
  -d '{"maxActiveLines":10}'
# Подставьте siteId из ответа:
curl http://127.0.0.1:8080/api/v1/sites/<siteId>/equipment-types
curl -X POST http://127.0.0.1:8080/api/v1/sites/<siteId>/blueprints \
  -H 'X-Zavod-Dev-Write: true'
```

Далее API поддерживает `PUT .../blueprints/{id}/draft` (`expectedRevision` и `snapshot`), `POST .../validate`, `GET .../impact?from=1&to=2`, отдельные `GET/PUT .../layouts/{viewId}`. Запросы изменения требуют `X-Zavod-Dev-Write: true`; внешний IP и недоверенный браузерный Origin запрещены. **Это локальная песочница, а не production-аутентификация.** В обычном профиле `denyAll`, API-контроллеры `dev-local` не создаются. Никаких PLC write-операций.

## BCQ-1 — бизнес-ядро прежде новых модулей

**Текущий приоритет:** доказательство целостности процессов, ревизий и переходов `Create → Validate → Save → Approve → Publish → Activate → Revise`, затем повторные испытания восстановления и только после них расширение React Studio / OT / ML.

- Единый реестр 15 бизнес-процессов, 32 инвариантов `BR-001…032` и 25 сценариев `SC-BCQ-001…025`: [раздел 28 мастер-ТЗ](docs/ZAVOD-MASTER-SPEC.md).
- RED→GREEN: проверка типов циклов MATERIAL/ITEM_FLOW/INSTRUMENT, измерительных capabilities, current-only resource contention, concurrent approval и запрет повторной активации.
- Подтверждённый сквозной JPA/PostgreSQL business journey: 99 DRAFT lines / 10 selected; L037→L038 Impact; immutable ревизии; два последовательных ActivationSet с историческим pointer; отказ при ошибочной новой revision.
- Recovery: уничтожение и повторное создание Spring ApplicationContext с одной PostgreSQL; полный 99-line DRAFT (853 equipment, 64 transfers, 14 dependencies) и активная утверждённая конфигурация читаются без потери связей.
- Transactional integrity: PostgreSQL CHECK fault injection принудительно отклоняет финальный указатель активной конфигурации, а тест проверяет полный rollback вставки ActivationSet.
- **Открыто:** настоящий restart процесса/JVM, резервное копирование и восстановление PostgreSQL, потеря связи с БД посреди транзакции, site-wide shared resource arbitration, внешний security review и четырёхглазное утверждение.

Исполняемые проверки: `bash scripts/test-blueprint-core.sh` (JDK 21) и `mvn test` (доступный Docker для PostgreSQL Testcontainers). **Зелёный CI не эквивалентен промышленной сертификации.**

## Следующие этапы

**Engineering Builder → Smart Line Designer → SCADA/Telemetry → Production Intelligence → Predictive Maintenance → Production Optimizer.**

INT-001…INT-008 документированы в каноническом ТЗ как **будущие требования**. ML и автоматические команды PLC не входят в текущий P0.

## Рабочий процесс

**Единственная активная ветка разработки — `main`.** Новых feature-веток не создаём без отдельного решения. Разработка на `main` сопровождается обязательным CI и явным указанием непокрытых рисков. Исторические PR #1–#4 остаются в GitHub для трассируемости.

## Safety

Без управляющих команд физическому PLC. Safety interlocks и E-stop остаются в независимых аппаратных/PLC-системах. Реальные OPC UA/Modbus read-only только после OT-аудита. Лабораторная модель не сертифицирована для управления производством.
