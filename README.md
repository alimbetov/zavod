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
- Сетевые HTTP API пока отсутствуют; подключение внешнего провайдера пользователей, provisioning площадок, раздельные контуры утверждения, полноценный security audit и идемпотентность остаются следующими задачами.

## Ещё НЕ реализовано / НЕ квалифицировано

- Визуальный React Blueprint Studio: standalone-прототип пока не перенесён.
- Подтверждённая эксплуатационная квалификация процесса утверждения (внешний identity provider, доверенное администрирование членов площадки, полноценный аудит и disaster recovery).

- Проверка полного каталога и физического процесса для всех 99 синтетических линий; реальный line capacity / material balance.
- 99 физических PLC, промышленная SCADA/MES, real hardware/OT FAT/SAT, HMI и управление реальным оборудованием.

**Технологии:** Java 21 / Spring Boot / JPA / PostgreSQL; React + TypeScript + React Flow (следующий этап); бесплатный OSS (Apache-2.0 предпочтительно, MIT и другие совместимые permissive лицензии допустимы с уведомлениями). Vaadin Flow не используется. Liquibase/Flyway пока нет. `spring.jpa.hibernate.ddl-auto=update` только для DEV; в PROD `validate` и отдельная квалификация схемы.

## Следующие этапы

**Engineering Builder → Smart Line Designer → SCADA/Telemetry → Production Intelligence → Predictive Maintenance → Production Optimizer.**

INT-001…INT-008 документированы в каноническом ТЗ как **будущие требования**. ML и автоматические команды PLC не входят в текущий P0.

## Рабочий процесс

**Единственная активная ветка разработки — `main`.** Новых feature-веток не создаём без отдельного решения. Разработка на `main` сопровождается обязательным CI и явным указанием непокрытых рисков. Исторические PR #1–#4 остаются в GitHub для трассируемости.

## Safety

Без управляющих команд физическому PLC. Safety interlocks и E-stop остаются в независимых аппаратных/PLC-системах. Реальные OPC UA/Modbus read-only только после OT-аудита. Лабораторная модель не сертифицирована для управления производством.
