# Changelog

Формат — [Keep a Changelog](https://keepachangelog.com/ru/1.1.0/); версии — [SemVer](https://semver.org/lang/ru/).

## [0.1.0] — 2026-09-01

Первый размеченный релиз шлюза сбора данных.

### Добавлено
- Двухпротокольный сбор одновременно: **OPC UA** (Eclipse Milo) + **Modbus TCP** (j2mod),
  2471 канал из общей базы (`tagId = node.id`).
- Публикация в **Kafka**: телеметрия (`scada.tags`), алармы (`scada-alarms`), события
  (`scada-events`); приём команд записи (`scada-commands` → `scada-command-results`).
- Объектная модель прибора: монитор восстанавливает device/field/type из своего реестра по ключу.
- **Надёжность:** авто-переподключение (супервизор + токен поколения против flapping),
  таймауты OPC UA/Modbus, журнал `event_log`, edge-триггерные алармы (флаг, по умолчанию OFF).
- **Наблюдаемость:** метрики Prometheus на `/actuator/prometheus`.
- **Схема БД под Flyway** (`ddl-auto=validate`); PLC-симулятор (Python) с проигрыванием
  5-суточного архива `BN1_MCA1`.
- **CI/CD** (GitHub Actions): сборка + тесты на каждый PR, публикация docker-образа в GHCR на merge.
- **Контрактные тесты** телеметрии шлюз→монитор: unit (форма/типизация/маршрут) и
  EmbeddedKafka (реальный топик из конфига + сквозной round-trip).

### Изменено
- Декомпозиция god-класса `OpcUaClientServiceDB` (−43%): вынесены ValueCodec, CommandService
  (через порты, DIP), AlarmEvaluator, TelemetryProcessor, ModbusBatchReader и др.; 55 тестов.
- Обновление **Spring Boot 3.2.4 → 3.5.16** (off EOL, Flyway 10).
- **Ускорение:** батч-чтение Modbus (FC03 блоками, ~30 запросов/цикл вместо 1877),
  батч-чтение OPC UA (1 read на контроллер), батч-запись телеметрии, кэши NodeId/тегов.
- Идемпотентный Kafka-продюсер (`enable.idempotence`, `acks=all`), snappy-сжатие.
- Сплошная документация кода (класс/метод/поле) и синхронизация доков с кодом.

[0.1.0]: https://github.com/savushkin-dev/scada-gateway/releases/tag/v0.1.0
