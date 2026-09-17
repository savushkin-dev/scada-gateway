# SCADA Gateway

[![CI/CD](https://github.com/savushkin-dev/scada-gateway/actions/workflows/ci.yml/badge.svg)](https://github.com/savushkin-dev/scada-gateway/actions/workflows/ci.yml)

Промышленный шлюз сбора данных для АСУ ТП: опрашивает контроллеры ПЛК по **OPC UA** и
**Modbus TCP**, привязывает сигналы к общей базе каналов и публикует телеметрию, события
и алармы в **Apache Kafka** для монитора, редактора и мобильного приложения.

> Полная спецификация — [`docs/SPECIFICATION.md`](docs/SPECIFICATION.md).
> Текстовая UML-схема (component / sequence / deployment) — [`docs/architecture.puml`](docs/architecture.puml).
> Разработка (сборка / тесты / PR) — [`CONTRIBUTING.md`](CONTRIBUTING.md); история версий — [`CHANGELOG.md`](CHANGELOG.md).

---

## Стек

| | |
|---|---|
| **Шлюз** | Spring Boot 3.5.16 · Java 21 · Eclipse Milo (OPC UA) · j2mod (Modbus) · LuaJ (PAC) · Spring Kafka |
| **Хранилище** | PostgreSQL 16 (теги, телеметрия, журнал `event_log`) |
| **Шина** | Apache Kafka (KRaft), JSON-сообщения |
| **Симулятор** | Python — проигрывает 5-суточный архив `BN1_MCA1` в реальном времени в родном формате контроллеров |
| **Развёртывание** | Docker Compose (одна команда `./up.sh`) |

## Быстрый старт

```bash
./up.sh                 # соберёт jar и поднимет postgres + kafka + simulator + gateway
./up.sh --logs          # то же + логи шлюза
./up.sh down            # остановить (данные БД сохранятся)
./up.sh down -v         # остановить и стереть БД
```

Порты на хост: шлюз `:8888`, Kafka `:9094` (для монитора), PostgreSQL `:5433`,
симулятор `:4840` (OPC UA) / `:5020` (Modbus) / `:10000` (PAC).

```bash
curl -s http://localhost:8888/actuator/health          # {"status":"UP"}
```

## Архитектура

```
Контроллеры (ПЛК)            SCADA Gateway                 Верхний уровень
┌───────────────────┐      ┌────────────────────┐        ┌──────────────┐
│ Phoenix / OPC UA   │────▶│ OPC UA·Modbus·PAC   │        │ Монитор       │
│ WAGO    / Modbus   │────▶│ обработка · алармы  │──Kafka▶│ Редактор      │
│ PAC / driver-master│────▶│ события · reconnect │        │ Мобильное     │
└───────────────────┘      └─────────┬──────────┘        └──────────────┘
                                     │ tagId = node.id
                              ┌──────▼───────┐
                              │ База каналов  │  channel_dump.sql (EAV)
                              └──────────────┘
```

Идентичность сигнала сквозная: `tagId = channel.node.id` = **Kafka-key**. Объектную модель
прибора (device/field/type) монитор восстанавливает из своего реестра по этому ключу — на
проводе телеметрии её нет (тонкий триплет `value` / `quality` / `timestamp`).

## Ключевые возможности

- **Три протокола одновременно** — OPC UA (типизированные узлы), Modbus TCP (float32 LE / int16)
  и PAC (`driver-master`, Savushkin/ptusa: TCP + zlib(Lua), опрос через LuaJ).
- **Привязка к базе каналов** — 2517 каналов из `channel_dump.sql`, `tagId = node.id`.
- **Адекватные данные** — измерения идут из архива BN1_MCA1 по своей физической величине
  (очищены от кодов обрыва датчика), уставки — наладочные константы по прошивке ptusa;
  модель — `tools/build_data_model.py`, проверка по всему архиву — `tools/check_data_model.py`.
- **Авто-переподключение** — супервизор (`@Scheduled`), детект «тихой» смерти сессии,
  токен поколения против flapping.
- **Журнал и события** — `event_log` в БД + топики `scada-events` / `scada-alarms`.
- **Алармы по уставкам** — edge-триггер, пороги из перцентилей p1/p99 архива, гистерезис.
- **Команды оператора** — запись команд и уставок по OPC UA и PAC через `scada-commands`;
  Modbus и показания датчиков — только чтение (`REJECTED_NOT_WRITABLE`).

## Структура

```
scada-gateway/
├── SCADA-gateway/          # шлюз (Spring Boot)
├── plc-simulator/          # PLC-симулятор (Python): OPC UA + Modbus + PAC, replay архива
├── docs/                   # спецификация + UML-схема
├── docker-compose.yml      # единый стек (postgres + kafka + simulator + gateway)
└── up.sh                   # запуск одной командой
```

## Топики Kafka

| Топик | Направление | Содержимое |
|---|---|---|
| `scada.tags` | шлюз → монитор | значения тегов |
| `scada-alarms` | шлюз → монитор | постановка/снятие алармов |
| `scada-events` | шлюз → монитор | соединения, качество, системные события |
| `scada-commands` | монитор → шлюз | команды записи |
| `scada-command-results` | шлюз → монитор | результат команды |
