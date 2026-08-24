# План: диагностировать и закрыть RST на OPC UA handshake (баг №2)

Спутник к `gateway-opcua-write-issue.md` (там — что уже известно и проверено; здесь —
что делать дальше и как не сломать остальное при пуше). Контекст короче: контроллер 1
(Phoenix Contact, OPC UA) стабильно ловит `Connection reset` на этапе UASC Hello/Acknowledge,
воспроизводится всегда, причина не установлена — TCP-коннект проходит, протокол рвётся.
Таймауты/пул планировщика/видимость в `event_log` уже добавлены апстримом (`a5f4661`) — это
лечит последствия (зависания, слепоту), не сам RST.

## Шаг 1 — снять пакетный дамp (без него дальше гадание, не диагностика)

Единственный способ увидеть, кто рвёт соединение и на каком байте.

1. Поставить Wireshark с Npcap (опция **"Support raw 802.11 traffic"** не нужна, но
   **"Install Npcap in WinPcap API-compatible Mode"** — да).
2. В списке интерфейсов должен появиться **Npcap Loopback Adapter** — слушать именно его
   (порт 4840 живёт на loopback, `127.0.0.1`/`localhost`).
3. Фильтр: `tcp.port == 4840`.
4. Запустить захват, затем поднять шлюз (или дождаться очередной попытки супервизора —
   теперь это раз в 10 c, см. `event_log`, событие `CONNECT_FAILED`).
5. Остановить захват сразу после RST (счёт на секунды).

Что искать в дампе:
- Дошёл ли Hello-пакет от клиента (Milo) до сервера полностью, или TCP посылает RST раньше,
  чем Hello вообще собран?
- Если Hello дошёл — есть ли ответ сервера (Acknowledge) перед RST, или RST приходит вместо
  ответа?
- Кто шлёт RST — по `Source`/`flags` видно, чей стек (127.0.0.1 в обе стороны, но
  src/dst порт покажет направление).
- Размер и содержимое Hello-пакета (endpointUrl, receiveBufferSize, sendBufferSize) —
  сравнить с тем, что реально ожидает `asyncua` 1.1.8 (см. шаг 2).

## Шаг 2 — если дамп показывает валидный Hello и RST от сервера

Разбираться на стороне `plc-simulator` (Python, `asyncua`):

1. Поднять уровень логов до DEBUG для `asyncua` в `plc-simulator/simulator.py`
   (`logging.basicConfig(level=logging.DEBUG, ...)` или отдельный
   `logging.getLogger('asyncua').setLevel(logging.DEBUG)`), пересобрать образ, повторить
   попытку подключения. Сейчас там полностью тихо в момент RST — сервер либо не логирует
   исключение, либо роняет соединение на уровне ниже логики asyncua (asyncio task
   crash без catch).
2. Проверить `core/plc.py::init_opcua_server` — `security_policy=[NoSecurity]` совпадает с
   тем, что просит Milo (`SecurityPolicyType.NoSecurity` в `OpcUaClientConfig`,
   `OpcUaClientServiceDB.java:322-326`)? Несовпадение security policy — частая причина
   мгновенного разрыва без осмысленной ошибки на клиенте.
3. Проверить, не открыт ли уже где-то ДРУГОЙ клиент/сессия к этому же серверу с тем же
   `ApplicationUri` (`urn:scada:gateway`) — некоторые OPC UA серверы агрессивно рвут
   дублирующиеся сессии.

## Шаг 3 — если дамп показывает, что RST не от сервера (со стороны Windows/Docker)

1. Временно попробовать без `docker-proxy`: подключиться напрямую к внутреннему IP
   контейнера (`docker inspect scada-simulator --format '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}'`)
   вместо опубликованного порта — если заработает, проблема локализована в
   Windows-специфичном пробросе портов Docker Desktop (Hyper-V-бэкенд), не в коде ни одного
   из репозиториев. См. память `docker-hyperv-idle-ram` — на этой машине уже была
   нестабильность вокруг Hyper-V-VM.
2. Проверить исключения в Windows Defender / антивирусе для порта 4840 и для
   `java.exe`/`python.exe` — глубокая инспекция трафика иногда рвёт нестандартные бинарные
   протоколы на loopback.
3. Если ни то, ни другое — рассмотреть смену версии Docker Desktop или временный переход
   Modbus-only для тестов, пока OPC UA-путь не станет пригоден для диагностики на этой
   машине (это уже не фикс, а обходной путь для разработки).

## Чек-лист перед пушом (любого фикса из шагов 1-3)

В репозитории нет содержательных автотестов (`ScadaGatewayApplicationTests` — только
`contextLoads()`, даже пакет не совпадает с `com.scada.gateway`), так что проверка — целиком
руками, на живом стенде. Ничего не считается готовым без прогона по всем пунктам:

1. **Сборка чистая:**
   ```powershell
   $env:JAVA_HOME="C:\Users\<...>\.jdks\jbr-21.0.10"
   $env:PATH="$env:JAVA_HOME\bin;$env:PATH"
   cd Z:\Claude\Projects\scada-gateway\SCADA-gateway
   mvn -q -DskipTests package
   ```
   Без ошибок, без новых WARN о deprecated API.

2. **Полный holodный старт стенда** (`start-all.ps1 -Status` — все пункты `[OK]`, включая
   строку про анонс OPC UA — она обязана совпадать с ожидаемым, иначе это отдельная, уже
   виденная сегодня ловушка).

3. **OPC UA реально подключается** — в логе шлюза должно быть:
   ```
   ✅ OPC UA socket up: Phoenix Contact — опрос запущен
   ...
   🟢 Phoenix Contact: связь установлена
   ```
   и в `event_log`: `CONNECTED` (не только `CONNECTING`).

4. **Телеметрия по OPC UA реально идёт** — проверить топик `scada.tags` на свежие сообщения
   именно по OPC UA-тегам (не спутать с Modbus, они льются всегда и маскируют проблему):
   ```powershell
   cd C:\kafka_2.13-4.3.1
   .\bin\windows\kafka-console-consumer.bat --bootstrap-server localhost:9092 --topic scada.tags `
     --max-messages 500 --timeout-ms 15000 --property print.key=true --property key.separator=" | "
   ```
   грепнуть на `V_ST_1` / `V_M_1` (или другой известный OPC UA-путь) — должны быть свежие
   метки времени.

5. **Запись реально проходит и телеметрия обновляется** — живой прогон через фронт
   (`/monitor`, проект «Демо: рецепты и кнопка», кнопка «Клапан LINE1V0») ИЛИ вручную:
   отправить `writeTag('ST', ...)` и в логе шлюза увидеть
   `✍ OPC UA записано ... = ... (tag ...)`, статус в `scada-command-results` — `APPLIED`.

6. **Регресс по уже исправленному не откатился:**
   - `writeModbus` всё ещё резолвит контроллер через `controllerById`, не через
     `tag.getController()` напрямую (баг №1 не вернулся).
   - `writable`-проверка (`OpcUaClientServiceDB.java:774`) всё ещё на месте — запись в
     НЕ-writable тег обязана вернуть `REJECTED_NOT_WRITABLE`, а не молча пройти.
   - Modbus-контроллер (WAGO) как подключался, так и подключается — фикс OPC UA не должен
     задеть независимый протокол.

7. **Супервизор не зафлапал** — понаблюдать `event_log` минуту-две после старта: не должно
   быть чередования `CONNECTED`/`DISCONNECTED` каждые несколько секунд (симптом гонки
   генерации опроса, `pollGeneration`).

8. **git diff перед коммитом — только заявленное.** В прошлый раз в рабочем дереве
   одновременно лежали два несвязанных изменения (мой фикс `writeModbus` и чужая правка
   `replay_config.yaml`) — легко закоммитить лишнее. `git status`/`git diff --stat` перед
   `git add`, коммитить только то, что относится к текущему фиксу.

9. **Ветка/база актуальны.** Правильный upstream — `https://github.com/savushkin-dev/scada-gateway`
   (НЕ форк `EuZireael`, в который нет прав пуша). Перед пушом: `git fetch savushkin main`,
   `git log savushkin/main..HEAD` — что реально уедет, нет ли там чужих неожиданных коммитов
   из-за неверно выбранной базы.
