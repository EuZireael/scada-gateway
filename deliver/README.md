# SCADA — продюсер-сторона (для разработки монитора)

Готовые образы шлюза и симулятора + `docker-compose.yml`. Поднимает продюсер
целиком: шлюз опрашивает симулятор (реплей 5-суточного архива BN1_MCA1 по OPC UA,
Modbus и PAC) и публикует телеметрию в Kafka. Монитор подключается к Kafka и потребляет.

## Что внутри — ПОЛНОСТЬЮ ОФФЛАЙН (ничего не тянется из сети)
```
images/postgres-16.tar.gz           база шлюза (PostgreSQL 16)
images/cp-kafka.tar.gz              брокер (Confluent cp-kafka, KRaft)
images/scada-simulator-0.1.0.tar.gz симулятор (Python 3.11, OPC UA+Modbus+PAC, архив зашит)
images/scada-gateway-0.1.0.tar.gz   шлюз (Spring Boot, JRE 21)
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
Топик: **`scada.tags`**.

Быстрый просмотр (образ cp-kafka — утилита на PATH, без `/opt/...`):
```bash
docker exec scada-kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 --topic scada.tags \
  --property print.key=true --max-messages 20
```

## Формат сообщения
- **топик:** `scada.tags`
- **ключ** (String): полный путь канала (`channel.node.id_node`), напр.
  `Барановичи-1.BN1_MCA1.V_M_1.LINE1V0.M` — по нему монитор резолвит канал в базе.
- **значение** (JSON): **тонкий триплет** — значение + качество + время, больше в теле НИЧЕГО:
```json
{
  "value": 72.7,                  // типизированное: число или true/false
  "quality": "GOOD",              // GOOD / BAD
  "timestamp": 1784123375.57      // epoch-секунды (float)
}
```
Идентичность тега несёт **Kafka-key** (путь канала), а НЕ тело сообщения. Всё статическое —
прибор/поле/тип, единицы, контроллер — монитор достраивает из СВОЕГО реестра каналов
(`channel_dump.sql`) по этому ключу; на проводе этого нет. Так поток лёгкий, а дисплей
монитора не завязан на схему БД шлюза.

**Три протокола в одном потоке:** OPC UA + Modbus (2471 архивный канал) и PAC (`driver-master`,
7 демо-тегов с ключом `PAC_DEMO.*`). Наружу протокол не торчит — сообщения неразличимы.

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
