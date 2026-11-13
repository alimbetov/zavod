# Zavod IndustrialOS — обзор китайских модульных производственных линий и периферии для МСП

**Дата:** 2026-11-13 · **Статус:** исследование / продуктовая гипотеза, НЕ нормативное утверждение готовой совместимости.  
**Мастер-ТЗ:** [R0](../requirements/R0-master-spec.md) · **Архитектура:** [Universal Builder R0.3](../architecture/universal-industrial-builder-R0-3.md).

## 1. Выводы

Для малого и среднего бизнеса наиболее перспективен **ретрофит модульного оборудования**: производственная линия состоит из нескольких отдельных станций разных производителей с локальными HMI, PLC, частотниками и датчиками. Zavod должен обеспечивать **обнаружение возможностей, модель линии, мониторинг и производственную прослеживаемость**, а не переписывание PLC-программ.

**Важно:** предложения производителей/каталоги доказывают существование типовых комплектов, но не являются репрезентативной статистикой продаж, гарантией дешёвой интеграции или подтверждением спроса. Поддержку конкретной модели и протокола нужно проверять по документации/прошивке.

## 2. Десять предложенных отраслевых шаблонов

| # | Template key (proposed) | Тип | Модули технологического пути | Приоритет Zavod |
|---|---|---|---|---|
| 1 | `discrete.bottling.v1` | DISCRETE | Unscrambler → Filler → Capper → Labeler → Inspection | P0 |
| 2 | `discrete.bagging.v1` | DISCRETE/BATCH | Hopper → Weigher/Doser → Bagging → Sealer → Checkweigher | P0 |
| 3 | `batch.concrete.v1` | BATCH | Aggregate/Cement/Water → Scales/Dosers → Mixer → Discharge | P0 |
| 4 | `batch.feed-pellet.v1` | BATCH + downstream | Mill → Doser → Mixer → Pelletizer → Cooler → Sifter → Bagging | P1 |
| 5 | `continuous.water-system.v1` | CONTINUOUS/UTILITY | Tank → Pump → Filter → Flow/Pressure → Consumer | P0 |
| 6 | `discrete.block-press.v1` | DISCRETE/BATCH | Batching → Mixer → Block press → Pallet conveyor → Stacker/Curing | P1 |
| 7 | `continuous.plastic-recycling.v1` | CONTINUOUS | Shredder → Washer → Dryer → Extruder → Pelletizer → Cooling | P1 |
| 8 | `continuous.grain-processing.v1` | CONTINUOUS | Cleaner → Destoner → Huller → Separator → Sorter → Packing | P1 |
| 9 | `discrete.bakery.v1` | BATCH + DISCRETE | Dough mixer → Divider → Proofer → Oven → Cooling → Packing | P2 |
| 10 | `discrete.conveyor-sort.v1` | DISCRETE | Infeed → Detection → Diverter → Check → Outfeed/Pack | P1 |

These are **catalog templates**, not ten simultaneous lines mandated at one site: one plant can have 1–10 active lines of any profile in current product policy. Production profiles may include hybrid operations; no insistence that all nodes share one ProcessKind.

**Later candidates:** injection molding cell (hopper loader, dryer, injection press, chiller, robot, visual inspection), sheet-metal cutting/coiling and CNC machining cells. First require OEM device/PLC interfaces and extra motion/safety qualification. Injection molding auxiliaries are well represented in Chinese catalogues but not justified as R0 MVP.

## 3. Peripheral catalog (more important than industry count)

**Material handling:** hopper, silo, tank, hopper loader, vacuum feeder, belt/roller/screw conveyor, bucket elevator, transfer pump, diverter, gate.

**Process machines:** batch/continuous mixer, doser, liquid filler, granulator, crusher, extruder, press, oven, dryer, cooler, filter, sieve, mill.

**Actuation and mechanics:** motor, VFD, servo drive, cylinder, solenoid valve, proportional valve, pneumatically actuated gate, hydraulic power unit.

**Sensors / instrumentation:** load cell + weighing terminal, temperature, pressure, level, flow, encoder, photoelectric sensor, proximity, limit switch, vibration/current/energy meter, barcode reader.

**End-of-line quality:** checkweigher, reject actuator, metal detector, inkjet/laser printer, labeling system, machine vision, sorting gate.

**Shared utility assets:** compressed-air station, dust extractor, ventilation, thermal plant, water/chiller station, power-metering cabinet, vacuum pump, CIP where applicable.

**Controllers and gateways:** PLC, remote I/O, local HMI, protocol gateway, serial converter, industrial Ethernet switch; controller may be shared by multiple lines and equipment may have an embedded controller.

**Safety elements (document as independent equipment, never emulate as Java logic):** emergency-stop chain, guards/interlocks, safety relays, protective devices, safe drive functions. Read/monitor only after engineering approval; do not infer safety compliance from HMI status.

## 4. Product design rules

- Separate `EquipmentType`, physical `PlantAsset`/`EquipmentInstance`, `LineProfile`, `IndustryTemplate`.
- Equipment can be **standalone machine, skid, station, or composite Unit**; a machine may contain nested drives/sensors and still appear as one top-level canvas node.
- Five distinct port classes: `MATERIAL` (fluid, powder, granulate), `ITEM_FLOW` (bottles, boxes, pallets), `UTILITY` (power, water, compressed air), `SIGNAL` (telemetry), `CONTROL` (permitted intent). Avoid mixing physical connections with logical data bindings.
- Recipes belong to approved production definitions/MES, not to mixer class; support `recipe-capable`, `timed`, `continuous-setpoint`, `monitor-only` as declared capabilities.
- `Controller`/tags have `manufacturer, model, firmware, protocol, address, scaling, units, valueQuality, sourceTime, accessMode`; no unverified claims of automatic tag discovery.
- Three integration tiers: `SIMULATED`, `READ_ONLY_CONNECTED`, and `MANUAL_REPORTING`; command mode is separate, disabled and safety-gated.
- Line throughput can be `pcs/h`, `kg/h`, `m3/h`; report quality metrics by profile, not one universal "recipe".
- Catalogue scope: 20–30 **reusable primitives** eventually, but **start with existing 15 plus** 6 high-reuse nodes: checkweigher, photoeye, filler, capper, labeler, diverter. Do not declare them all implemented.
- Mapping to physical PLC is an adapter profile **per known controller**. OPC UA/Modbus capabilities vary by model/firmware. Default no PLC writes.

## 5. China automation findings

Inovance's official industrial automation catalogue lists PLC, HMI, VFD, servo, I/O and sensors. Xinje and Haiwell market packaging-focused PLC/IoT solutions. Chinese devices are not necessarily OPC UA devices: a maker may ship a local touch panel plus proprietary or Modbus/serial controls. **Start with vendor-disclosed OPC UA/Modbus, plus a read-only adapter/gateway.** No blanket compatibility assertion.

## 6. Lean validation plan

**Template A:** concrete batching (current scope): silo/scale/mixer, timed & recipe-capable modes.  
**Template B:** pump/utility (current scope): tank/pump/valve/pressure/flow, no recipe.  
**Template C:** filling/packaging (next): infeed/filler/capper/labeler/checkweigher and `ITEM_FLOW` graph.

**RED scenarios before implementation:**
1. Package line uses `ITEM_FLOW` ports; `WATER` cannot connect to bottle conveyor.
2. One machine represented as composite includes locally connected sensor/drive children without corrupting plant graph.
3. Two lines share one compressed-air asset with unique physical identity and separate references.
4. One line has mixed PLC and non-connected/manual stations; all statuses display source/quality correctly.
5. Per-line throughput uses compatible units with explicit conversions, rejects invalid mixing of `pcs/h` and `kg/h` without a defined conversion.
6. Recipe is optional on timed mixer, required only when selected process profile says so.
7. Pressure sensor is INSTRUMENT, not material flow-through unless modeled as an actual inline meter.
8. 10 active line policy honored; additional draft not lost.
9. Auto-generated HMI never creates PLC writes or claims active monitoring for virtual signals.
10. Save/export/import preserves template references, equipment ports and shared asset IDs.

## 7. Sources (primarily Chinese manufacturers / Chinese-language)

- Bottling/labeling: https://www.siyeauto.com/cpshow3997.html
- Packaging auxiliary equipment: https://www.chanferpack.com/zh/equipment/
- Small concrete HZS25 components: https://zcjxc.com.cn/new/2263.html
- Concrete block line: https://qunfeng-machine.en.made-in-china.com/product/cUNpSQbPOGhJ/China-Customizable-Automatic-Concrete-Block-Production-Line-Interlocking-Hollow-Solid-Brick-Machine.html
- Feed mill modular line: https://fdspchina.en.made-in-china.com/product/oStEmfFlbehK/China-Small-Floor-Space-Chicken-Birds-Pellet-Feed-Plant.html
- Plastic recycling equipment: https://www.klongchina.cn/products/
- Rice mill: https://sidamachine.en.made-in-china.com/product/PdkTCJuEJBRY/China-High-Quality-Fully-Automatic-Small-Rice-Polisher-Destoner-Milling-Processing-Machine.html
- Bakery: https://bossmai.en.made-in-china.com/product/yZFTYRgVZvUG/China-Kitchen-Equipment-Mini-Croissant-Production-Line-Baking-Oven-Dough-Sheeter-Bakery-Bread-Machine-Prices.html
- Packaging inspection: https://www.miqicw.com/en/
- Yizumi injection molding periphery: https://www.yizumi.com/en/news/product/IMM/guide-how-to-choose-a-pet-preform-injection-molding-machine
- Inovance industrial automation official: https://www.inovance.com/product?typeId=1
- Xinje packaging official: https://www.xinje.com/web/solutionInfo/index?id=34
- Haiwell IoT official: https://www.haiwell.com/about/haiwell-cn.html

## 8. Product discovery questions for on-site validation

1. What exact supplier/model/PLC/HMI are installed; can operator export register/tag map?
2. Are there real analog/serial/Ethernet readings or only lights/buttons?
3. Which machines share conveyors, compressors, silos or chillers?
4. What is measured: mass, volume, produced pieces, scrap, running hours?
5. Which manual logs and reconciliation headaches cost the shift the most?
6. What local network and cybersecurity constraints permit a passive gateway?
7. Is the goal **monitoring**, **manufacturing reporting**, or a qualified change to machine operations?
8. Which languages are used on the actual shop floor, and who can perform commissioning?

**Verdict:** prioritize integration of the small number of recurring machine and peripheral patterns, not a long catalogue of unsupported proprietary device models. Treat prices and vendor performance claims as unverified until independently checked.
