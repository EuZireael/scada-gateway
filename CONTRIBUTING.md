# Как участвовать в разработке SCADA Gateway

Короткий путеводитель для того, кто продолжает проект: как собрать, запустить,
протестировать и влить изменения. Общее устройство — в
[`docs/SPECIFICATION.md`](docs/SPECIFICATION.md) и [`docs/architecture.puml`](docs/architecture.puml).

## Что нужно установить

| Инструмент | Зачем | Версия |
|---|---|---|
| **JDK 21** | сборка и запуск шлюза | Temurin 21 (совпадает с Dockerfile) |
| **Docker / Podman** | поднять стенд (postgres + kafka + simulator + gateway) | на Fedora — нативный podman |
| **Python 3.11** | PLC-симулятор | 3.11 (на 3.14 баг asyncua-клиента) |

Maven ставить не надо — используется обёртка `./mvnw` (сама скачает нужный Maven).

## Собрать и прогнать тесты

```bash
cd SCADA-gateway
./mvnw -B verify        # компиляция + ВСЕ юнит/контрактные тесты + сборка jar
./mvnw test             # только тесты
./mvnw -Dtest=ValueCodecTest test   # один тест
```

Тесты — в `SCADA-gateway/src/test/java/com/scada/gateway/**` (JUnit 5 + Mockito, без
Spring; контрактный тест телеметрии поднимает встроенный Kafka в JVM — Docker не нужен).
Зелёный `verify` обязателен перед PR — CI прогонит ровно его.

Проверить Python-часть:

```bash
find plc-simulator loadtest tools -name '*.py' -print0 | xargs -0 python -m py_compile
```

## Запустить стенд

```bash
./up.sh                 # соберёт jar и поднимет весь стек одной командой
./up.sh --logs          # то же + логи шлюза
./up.sh down            # остановить (данные БД сохранятся)
./up.sh down -v         # остановить и стереть БД
```

Проверка: `curl -s http://localhost:8888/actuator/health` → `{"status":"UP"}`.
Порты: шлюз `:8888`, Kafka `:9094` (для монитора), PostgreSQL `:5433`, симулятор
`:4840` (OPC UA) / `:5020` (Modbus). Подробности — [`DEPLOY.md`](DEPLOY.md).

## Изменения схемы БД — только через Flyway

Схемой владеет Flyway (`ddl-auto=validate` только сверяет). Новую колонку/таблицу
добавляй **новой миграцией** `SCADA-gateway/src/main/resources/db/migration/Vn__описание.sql`,
а **не** через Hibernate. Иначе `validate` уронит старт.

## Рабочий процесс (Git / PR)

Канонический репозиторий — `savushkin-dev/scada-gateway`, работа идёт через форк.

1. Ветка от свежего `main`: `git switch -c тип/короткое-имя` (`feat/…`, `fix/…`, `docs/…`, `test/…`).
2. Коммить по-русски, по смыслу (что и зачем).
3. `git push` в свой форк (`origin`).
4. Открой **Pull Request** в `savushkin-dev:main`.
5. Дождись **зелёного CI** (см. ниже) и **Merge**. В `main` напрямую не пушим.

## CI/CD (GitHub Actions, `.github/workflows/ci.yml`)

- **На каждый PR:** собирается шлюз + гоняются все тесты (`Gateway build & test`),
  проверяется синтаксис Python, собирается docker-образ (валидация Dockerfile).
- **На merge в `main`:** дополнительно публикуется docker-образ в GHCR
  (`ghcr.io/savushkin-dev/scada-gateway:latest` + `:<sha>`).

Красный чек = чинить до мёржа. Смотреть прогоны — вкладка **Actions**.

## Стиль

- Комментарии — кратко и по делу, **по-русски**; объясняй *зачем*, а не пересказывай код.
- У класса — шапка-описание; у метода/поля — docstring там, где смысл не читается из имени.
- Держись стиля окружающего кода (именование, отступы, идиомы).

## Куда смотреть

- [`docs/SPECIFICATION.md`](docs/SPECIFICATION.md) — полная спецификация (протоколы, форматы Kafka, надёжность).
- [`docs/architecture.puml`](docs/architecture.puml) — component / sequence / deployment диаграммы.
- [`docs/CONTROL_AND_LOADTEST.md`](docs/CONTROL_AND_LOADTEST.md) — команды управления и нагрузочный стенд.
- [`CHANGELOG.md`](CHANGELOG.md) — что менялось по версиям.
