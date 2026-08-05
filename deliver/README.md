# SCADA — продюсер-сторона (для разработки монитора)

Готовые образы шлюза и симулятора + `docker-compose.yml`. Поднимает продюсер
целиком: шлюз опрашивает симулятор (реплей 5-суточного архива BN1_MCA1 по OPC UA
и Modbus) и публикует телеметрию в Kafka. Монитор подключается к Kafka и потребляет.

## Что внутри — ПОЛНОСТЬЮ ОФФЛАЙН (ничего не тянется из сети)
```
images/postgres-16.tar.gz           база шлюза (PostgreSQL 16)
images/cp-kafka.tar.gz              брокер (Confluent cp-kafka, KRaft)
images/scada-simulator-1.0.tar.gz   симулятор (Python 3.11, OPC UA+Modbus, архив зашит)
images/scada-gateway-1.0.tar.gz     шлюз (Spring Boot, JRE 21)
docker-compose.yml                  все 4 сервиса, pull_policy: never
load-images.sh                      загрузка всех образов
verify.sh                           смоук-тест: ждёт данные и печатает примеры
```

## Запуск (нужен только Docker или Podman, интернет НЕ нужен)
```bash
# 1. Загрузить все образы из tar
./load-images.sh
#   или вручную: docker load -i images/<каждый>.tar.gz

# 2. Поднять стек
docker compose up -d

# 3. Смоук-тест: дожидается телеметрии и печатает примеры из топика
./verify.sh
#   (в конце должно быть: "✅ Проверка пройдена")
```

## Как потреблять телеметрию (сторона монитора)
Брокер доступен **с хоста по `localhost:9094`** (внутри compose-сети — `kafka:9092`).
Топик: **`scada-telemetry`**.

Быстрый просмотр:
```bash
docker exec scada-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic scada-telemetry \
  --property print.key=true --max-messages 20
```

## Формат сообщения
- **топик:** `scada-telemetry`
- **ключ** (String): полный путь канала (`channel.node.id_node`)
- **значение** (JSON): заголовок `__TypeId__: telemetry`
```json
{
  "type": "TELEMETRY",
  "tagId": 6,                     // = channel.node.id из базы каналов → по нему резолвить канал
  "tagName": "Барановичи-1.BN1_MCA1.Параметры_станции.OBJECT1.PAR_MAIN[1].P_CZAD_S",
  "value": 72.7,                  // типизированное значение (число или true/false)
  "numericValue": 72.69,          // для графиков
  "stringValue": "72.7",
  "quality": "GOOD",
  "timestamp": 1784123375.57,     // epoch-секунды
  "controllerId": 1               // 1 = Phoenix/OPC UA (дискретные), 2 = WAGO/Modbus (аналоговые)
}
```
`tagId` — это `node.id` из общей базы каналов (`channel_dump.sql`). Монитор по нему
находит канал, имя (`id_node`) и всю иерархию. Задействованы все 2471 канал.

## Остановить
```bash
docker compose down            # данные БД сохранятся в volume pgdata
docker compose down -v         # + удалить данные
```

## Примечания
- Kafka: если консюмер монитора внутри той же compose-сети — используйте `kafka:9092`,
  если с хоста/другого проекта — `localhost:9094`.
- Порты на хосте: 9094 (kafka), 8888 (шлюз), 4840/5020 (симулятор, опционально).
- Образы собраны rootless-podman'ом, формат docker-archive — грузятся и в Docker, и в Podman.
