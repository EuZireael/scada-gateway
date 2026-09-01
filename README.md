# SCADA Gateway

[![CI/CD](https://github.com/savushkin-dev/scada-gateway/actions/workflows/ci.yml/badge.svg)](https://github.com/savushkin-dev/scada-gateway/actions/workflows/ci.yml)

Промышленный шлюз сбора данных для АСУ ТП: опрашивает контроллеры ПЛК по **OPC UA** и
**Modbus TCP**, привязывает сигналы к общей базе каналов и публикует телеметрию, события
и алармы в **Apache Kafka** для монитора, редактора и мобильного приложения.

> Полная спецификация — [`docs/SPECIFICATION.md`](docs/SPECIFICATION.md).
> Текстовая UML-схема (component / sequence / deployment) — [`docs/architecture.puml`](docs/architecture.puml).

---

## Стек

| | |
|---|---|
| **Шлюз** | Spring Boot 3.2.4 · Java 21 · Eclipse Milo (OPC UA) · j2mod (Modbus) · Spring Kafka |
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
симулятор `:4840` (OPC UA) / `:5020` (Modbus).

```bash
curl -s http://localhost:8888/actuator/health          # {"status":"UP"}
```

## Архитектура

```
Контроллеры (ПЛК)            SCADA Gateway                 Верхний уровень
┌───────────────────┐      ┌────────────────────┐        ┌──────────────┐
│ Phoenix / OPC UA   │────▶│ OPC UA + Modbus     │        │ Монитор       │
│ WAGO    / Modbus   │────▶│ обработка · алармы  │──Kafka▶│ Редактор      │
└───────────────────┘      │ события · reconnect │        │ Мобильное     │
                           └─────────┬──────────┘        └──────────────┘
                                     │ tagId = node.id
                              ┌──────▼───────┐
                              │ База каналов  │  channel_dump.sql (EAV)
                              └──────────────┘
```

Идентичность сигнала сквозная: `tagId = channel.node.id`, объектная модель прибора
передаётся в `metadata{device, field, deviceType}` — монитор собирает канал в объект-устройство.

## Ключевые возможности

- **Два протокола одновременно** — OPC UA (типизированные узлы) и Modbus TCP (float32 LE / int16).
- **Привязка к базе каналов** — 2471 канал из `channel_dump.sql`, `tagId = node.id`.
- **Авто-переподключение** — супервизор (`@Scheduled`), детект «тихой» смерти сессии,
  токен поколения против flapping.
- **Журнал и события** — `event_log` в БД + топики `scada-events` / `scada-alarms`.
- **Алармы по уставкам** — edge-триггер, пороги из перцентилей p1/p99 архива, гистерезис.
- **Команды оператора** — запись значения в OPC UA-тег через `scada-commands`.

## Структура

```
scada-gateway/
├── SCADA-gateway/          # шлюз (Spring Boot)
├── plc-simulator/          # PLC-симулятор (Python): OPC UA + Modbus, replay архива
├── docs/                   # спецификация + UML-схема
├── docker-compose.yml      # единый стек (postgres + kafka + simulator + gateway)
└── up.sh                   # запуск одной командой
```

## Топики Kafka

| Топик | Направление | Содержимое |
|---|---|---|
| `scada-telemetry` | шлюз → монитор | значения тегов |
| `scada-alarms` | шлюз → монитор | постановка/снятие алармов |
| `scada-events` | шлюз → монитор | соединения, качество, системные события |
| `scada-commands` | монитор → шлюз | команды записи |
| `scada-command-results` | шлюз → монитор | результат команды |
