# Запуск на сервере (гибрид: инфраструктура в Docker, приложения на хосте)

PostgreSQL и Kafka поднимаются в Docker, а **симулятор (Python)** и **шлюз (Java)**
запускаются на хосте вручную. Так удобно отлаживать и видеть логи.

## 0. Предусловия на сервере

- Docker + Docker Compose v2 (`docker compose version`)
- JDK 21 (`java -version`)
- Python 3.11 или 3.12 + `python3-venv`
  > На Python 3.14 серверная часть симулятора работает, но это «край» —
  > для сервера берите 3.12.

Порядок запуска: **инфраструктура → симулятор → шлюз** (шлюз опрашивает OPC UA
симулятора, поэтому симулятор должен подняться раньше).

---

## 1. Инфраструктура (PostgreSQL + Kafka) в Docker

```bash
cd SCADA-gateway
docker compose up -d
docker compose ps          # postgres должен быть (healthy)
```

Поднимаются:

| Сервис     | Порт на хосте | Назначение                          |
|------------|---------------|-------------------------------------|
| postgres   | 5433          | БД `scada_db` (scada_user/scada_password) |
| kafka      | 9092          | брокер                              |
| zookeeper  | —             | внутренний                          |

Данные БД хранятся в volume `pgdata` (`docker compose down` их не трогает;
полная очистка — `docker compose down -v`).

Совпадает с `application.yaml`: `jdbc:postgresql://localhost:5433/scada_db`,
`bootstrap-servers: localhost:9092`. Таблицы создаёт сам Hibernate
(`ddl-auto: update`), топики Kafka — брокер при первом сообщении.

---

## 1b. Подготовка БД (скрипты в `SCADA-gateway/db/`)

БД целиком **воспроизводимая**: теги/контроллер шлюз заливает из `application.yaml`
при каждом старте, телеметрия набегает из replay. Поэтому переносить дамп с другой
машины не нужно — наоборот, чужие данные ломают `IDENTITY`-последовательности
(ошибка `duplicate key value violates unique constraint "tags_pkey"`).

| Скрипт | Когда применять |
|--------|-----------------|
| `init_db.sql` | Только если на сервере **ещё нет** базы `scada_db` / роли `scada_user`. Запуск от суперпользователя: `sudo -u postgres psql -f init_db.sql` |
| `reset_db.sql` | **Если ловите `duplicate key` или хотите чистый старт.** Дропает таблицы — шлюз пересоздаёт схему и 170 тегов с нуля. |
| `fix_sequences.sql` | Альтернатива `reset_db.sql` без удаления данных: только чинит счётчики id (телеметрия сохраняется). |

```bash
cd SCADA-gateway/db
# чистый старт (рекомендуется при ошибке duplicate key):
psql -h localhost -p 5433 -U scada_user -d scada_db -f reset_db.sql
# либо без потери данных:
# psql -h localhost -p 5433 -U scada_user -d scada_db -f fix_sequences.sql
```

> Порт/имя/пароль — как в `application.yaml` (5433 / scada_db / scada_user / scada_password).
> Таблицы (`controllers`, `tags`, `telemetry`, `event_log`) создаёт сам Hibernate
> при старте шлюза — вручную их заводить не надо.

---

## 2. Симулятор (Python, replay архива)

```bash
cd plc-simulator
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

# Артефакты replay уже собраны в репозитории. Пересобрать из SQL (при желании):
python tools/build_archive.py "../BN1_MCA1 Архив тегов/BN1_MCA1.sql"

# Запуск воспроизведения архива (170 тегов, OPC UA на :4840)
python simulator.py config/replay_config.yaml
```

Скорость/зацикливание правятся в `config/replay_config.yaml`, секция `replay`:
- `speed: 720.0` — весь 5-суточный архив за ~10 минут (1.0 = реальное время);
- `loop: true` — по достижении конца начинать сначала.

OPC UA-узлы публикуются со строковыми nodeId вида `ns=2;s=2A050000`.

---

## 3. Шлюз (Spring Boot, Java 21)

```bash
cd SCADA-gateway
./mvnw clean package -DskipTests
java -jar target/SCADA-gateway-0.0.1-SNAPSHOT.jar
```

Шлюз слушает HTTP на `:8888`, подключается к postgres (5433), kafka (9092)
и OPC UA симулятора (`opc.tcp://127.0.0.1:4840`).

> Чтобы читать **все 170 архивных тегов**, блок `opcua.servers[].tags` в
> `src/main/resources/application.yaml` нужно заменить содержимым
> `plc-simulator/config/gateway_opcua_tags.yaml` (сейчас в шлюзе 10 старых
> демо-тегов). Без замены пойдут только они.

---

## 4. Проверка

```bash
# здоровье шлюза
curl -s http://localhost:8888/actuator/health

# телеметрия в БД
docker exec -it scada-postgres psql -U scada_user -d scada_db \
  -c "select count(*), max(timestamp) from telemetry;"

# сообщения в Kafka
docker exec -it kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 --topic scada-telemetry --max-messages 5
```

---

## Остановка

```bash
# симулятор и шлюз — Ctrl+C в их терминалах
cd SCADA-gateway && docker compose down      # инфраструктура (данные сохранятся)
```

## Заметки

- Если подключаетесь к серверу извне — откройте порт `8888` (HTTP шлюза);
  `4840/5433/9092` нужны только локально между сервисами.
- Чтобы процессы не падали при выходе из ssh — запускайте в `tmux`/`screen`
  или оформите как systemd-юниты (это уже не гибрид, а нативный вариант).
