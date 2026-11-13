# IndustrialOS — OT Safety & Cybersecurity Baseline v0.1

**Статус:** обязательные проектные ограничения. Не является HAZOP, SIL/PL assessment, safety case, сертификатом или разрешением на эксплуатацию.

## 1. Fundamental safety boundary

IndustrialOS R0/R1/R2 проектируется как инженерный редактор, безопасный симулятор, конфигурационный сервис и шлюз **read-only телеметрии**. Нельзя описывать его как промышленный safety controller или replacement PLC/HMI. Безопасное циклическое управление, interlocks, E-stop, пусковые цепи, permissive checks, защита электродвигателей, контроль уровня/давления/температуры и все safety-critical функции выполняются локально независимым, квалифицированным контроллером и физическими защитными устройствами согласно проекту объекта.

Потеря Java Backend, браузера, Ethernet, облака или gateway не должна отключать, обходить либо деградировать локальные защитные блокировки. Источником истины для состояния оборудования является PLC/local system; UI отображает измерения с качеством и возрастом данных.

## 2. Классификация режимов

| Режим | Взаимодействие с реальным PLC | Разрешение |
|---|---|---|
| DESIGN | отсутствует | R0 |
| SIMULATION | отсутствует, только виртуальные устройства | R0 |
| SHADOW_READ_ONLY | OPC UA/Modbus чтение, no writes, no method invocation | R2, после допуска на стенд |
| COMMISSIONED_MONITORING | промышленная сеть, наблюдение в эксплуатации, no writes | после SAT monitoring |
| COMMAND_CAPABLE (будущий) | ограниченные allowlisted команды через независимый PLC-контур | отдельное ТЗ/HAZOP/FAT/SAT и approval |
| SAFETY_LOGIC | недопустима в Java/Web/Cloud | никогда |

Запрет записи должен обеспечиваться не только выключенной UI-кнопкой: права учётной записи PLC, сетевые ACL, read-only adapter SPI, отсутствие write API и интеграционные негативные тесты.

## 3. Threat model и границы доверия

Актуальные угрозы: подмена конфигурации линии, компрометация сервисной учётной записи, проникновение из IT в OT, небезопасный удалённый доступ, replay/duplicate telemetry, timestamp spoofing, loss of communication, неверный register mapping, нарушение полноты журнала, ошибочная интерпретация BAD quality как реального значения, отказ датчика, устаревшие сертификаты.

Минимальные зоны:
- OT Field: датчики, приводы, физические E-stop.
- OT Control: PLC/local HMI/safety controller.
- Industrial DMZ / Edge: шлюз, ограниченный однонаправленный логический поток телеметрии; реальную сетевую односторонность не заявлять без соответствующих средств.
- Plant IT: management backend и engineering browser.
- Optional enterprise/cloud: только через контролируемый шлюз, никакого прямого доступа к PLC.

Классические firewalls/VLAN/ACL, разрешённые направления трафика, отдельные сервисные учётные записи, deny by default, VPN + MFA для авторизованного сервисного доступа. Никаких публичных OPC UA/Modbus endpoint.

## 4. Требования к OPC UA / Modbus

OPC UA:
- Защищённый канал, верификация сертификата и application identity, endpoint allowlist.
- Запрет insecure SecurityPolicy=None для реального deployment за исключением документированного изолированного лабораторного стенда.
- Только Subscription/Read в R2; write/method calls отсутствуют в коде или блокируются аппаратными/ACL ограничениями.
- Качество и sourceTimestamp обязательны; при BAD/UNCERTAIN явное отображение.

Modbus TCP:
- Не рассчитывать на встроенную аутентификацию классического Modbus TCP; изоляция подсети и сетевые ACL обязательны.
- Разрешать только read function codes на уровне адаптера и сети где возможно, отсутствие writes в интерфейсе; read-only binding credentials не всегда реализуемы конкретным PLC, поэтому усиливать сетевые и организационные меры.
- Endianness, подписанность, масштаб, регистр и unit выбираются на основании официальной карты производителя и тестируются с симулятором.

Никаких runtime-перепривязок тегов в production без ревизии, review и commissioning.

## 5. Secure development / operational requirements

- RBAC least privilege, утверждение публикации схемы отдельной ролью, аудит actor/time/oldHash/newHash.
- Сертификаты/секреты вне git, секреты в конфигурации с ограниченными правами, ротация и отзыв.
- TLS для интерфейсов IT; mTLS для соединений между доверенными сервисами по оценке рисков.
- SBOM, dependency/vulnerability scanning, подпись релизных артефактов по мере введения production.
- Параметры telemetry: GOOD/BAD/UNCERTAIN, stale age, device disconnect и lastSeen; старые данные не показывать как live.
- Rate limit, bounded queues, backpressure, retry jitter, circuit breaker по месту; в случае перегрузки деградирует UI/телеметрия, а не локальная безопасность.
- Логи не содержат паролей, секретов PLC, приватных ключей и raw access tokens.
- Отдельные backup/restore, incident response и patch window; любое обновление на объекте с rollback-plan.
- При расхождении часов фиксировать разницу, source/device timestamp и time received, не подделывать «текущее» измерение.

## 6. Условия для будущей возможности команд

До первого write должны быть оформлены и утверждены:
1. Технологическое описание конкретной установки и OEM-документация.
2. HAZOP / risk assessment + hazards and mitigations, согласованные ответственным инженером.
3. Command matrix: кто, когда, что, как подтверждает, допустимое состояние PLC, ограничения диапазона.
4. PLC-side validation/permissives/interlocks и независимый E-stop.
5. Локальная авторизация, запрет команд из публичного облака, временные разрешения и audit command/ack.
6. Safe failure states, потеря связи, duplicate command, replay, stale state, partial failure.
7. FAT на стенде, затем SAT на объекте, испытания восстановления, план обслуживания.
8. Юридическое/регуляторное соответствие применимое к конкретной стране и машине.
9. Письменная приёмка ответственным инженерным владельцем.

До выполнения всех пунктов — **NO-GO** на команды реальному оборудованию.

## 7. Отсылки

- NIST SP 800-82 Rev.3 FINAL: https://csrc.nist.gov/pubs/sp/800/82/r3/final (Rev.4 опубликован как проект в сентябре 2026).
- ISA/IEC 62443: https://www.isa.org/standards-and-publications/isa-standards/isa-iec-62443-series-of-standards
- OPC UA Reference: https://reference.opcfoundation.org/
- Modbus: https://www.modbus.org/modbus-specifications

Ориентация на стандарты не означает подтверждённого соответствия: требуется независимая инженерная оценка на конкретной площадке.
