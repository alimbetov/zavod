# Zavod IndustrialOS

**Единый нормативный источник истины:** [docs/ZAVOD-MASTER-SPEC.md](docs/ZAVOD-MASTER-SPEC.md)

**Статус:** Draft / на согласование · 13 ноября 2026  
**Архитектура:** React + TypeScript + React Flow (UI), Java 21 / Spring Boot / PostgreSQL (Backend), отдельный Industrial Edge read-only (будущий этап).  
**Продукт:** универсальный инженерный конструктор 1–10 активных разнотипных линий на площадке, с будущими SCADA/HMI/MES.  
**Политика зависимостей:** бесплатный OSS; Apache-2.0 предпочтительно; MIT/BSD/ISC допустимы после проверки обязательств и уведомлений. React и React Flow — MIT, Vaadin Flow в MVP не используется.

> Репозиторий на этом этапе в основном содержит **ТЗ**. Java equipment catalogue находится в отдельной рабочей ветке PR #2, а React Line Designer R0.1 — во внешнем архиве, ещё не импортирован. Реальная SCADA/MES и PLC интеграция **не реализованы**. Safety-critical управление остаётся в независимых PLC и защитных цепях.

## Документация

**Только один мастер-документ:** [ZAVOD-MASTER-SPEC.md](docs/ZAVOD-MASTER-SPEC.md)

Он включает бизнес-сценарии, модель Plant/Line/Equipment/Port/Signal, периферию и отраслевые линии, роли, авторизацию, архитектуру, требования FR/NFR, Industrial Edge, OT-безопасность, 70 запланированных сценариев, дорожную карту и решения DEC. Никаких параллельных ТЗ и отдельных roadmap/safety/test файлов.

Предыдущие документы сохранены в истории Git, но удалены из рабочей структуры, чтобы избежать расхождений.

## Рабочий процесс

Scenario Design → RED → Test Review → GREEN → Regression → Independent Review → Draft PR → Merge.  
Изменения проекта только в рабочих ветках, `main` — базовая ветка после review.

## Действующие PR

- [#1 — Консолидация ТЗ](https://github.com/alimbetov/zavod/pull/1) (Draft)
- [#2 — Java Smart Equipment Library](https://github.com/alimbetov/zavod/pull/2) (Draft; базируется на документационной ветке, потребует актуализации ссылок перед review)

## Риск промышленной безопасности

По умолчанию нет команд реальному PLC. Виртуальная симуляция не подключена к исполнительным механизмам. OPC UA/Modbus read-only только после отдельного OT review. Ограниченные команды на реальные установки — отдельный safety-gated release с FAT/SAT.
