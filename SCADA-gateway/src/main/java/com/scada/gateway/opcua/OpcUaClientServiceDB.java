package com.scada.gateway.opcua;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scada.gateway.model.entity.ControllerEntity;
import com.scada.gateway.model.entity.TagEntity;
import com.scada.gateway.model.entity.TelemetryEntity;
import com.scada.gateway.service.ConfigurationService;
import com.scada.gateway.repository.TelemetryRepository;
import com.scada.gateway.kafka.producer.TelemetryProducer;
import com.scada.gateway.kafka.producer.AlarmProducer;
import com.scada.gateway.modbus.ModbusClientService;
import com.scada.gateway.service.EventLogService;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.transaction.Transactional;

import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.api.config.OpcUaClientConfig;
import org.eclipse.milo.opcua.stack.client.DiscoveryClient;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Service
public class OpcUaClientServiceDB {

    private static final Logger log = LoggerFactory.getLogger(OpcUaClientServiceDB.class);

    private final ConfigurationService configurationService;
    private final TelemetryRepository telemetryRepository;
    private final TelemetryProducer telemetryProducer;
    private final AlarmProducer alarmProducer;
    private final ModbusClientService modbusClientService;
    private final EventLogService eventLogService;

    private final Map<Long, OpcUaClient> opcClients = new ConcurrentHashMap<>();
    private final Map<Long, ExecutorService> executors = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> runningStatus = new ConcurrentHashMap<>();
    /** «Поколение» опроса на контроллер. При переподключении растёт → старый поток
     *  опроса перестаёт быть текущим и выходит, даже если runningStatus снова true.
     *  Без этого старый и новый потоки опрашивали один контроллер и «дёргали»
     *  состояние связи (flapping DISCONNECTED/CONNECTED). */
    private final Map<Long, Long> pollGeneration = new ConcurrentHashMap<>();

    // --- Состояние связи с контроллерами (для авто-переподключения и журнала) ---
    /** true = связь есть. Переходы журналируются и шлются событием в Kafka. */
    private final Map<Long, Boolean> connected = new ConcurrentHashMap<>();
    /** Момент последнего успешного чтения (ms) — для детекта «тихой» смерти OPC UA. */
    private final Map<Long, Long> lastGoodReadMs = new ConcurrentHashMap<>();
    /** Идёт ли попытка переподключения (защита от гонки супервизора). */
    private final Map<Long, Boolean> reconnecting = new ConcurrentHashMap<>();
    /** Контроллер по id — чтобы супервизор знал, кого поднимать. */
    private final Map<Long, ControllerEntity> controllerById = new ConcurrentHashMap<>();
    /** Считаем ошибки чтения подряд — чтобы не спамить лог (троттлинг). */
    private final Map<Long, Integer> readErrorStreak = new ConcurrentHashMap<>();

    private final Map<Long, TagEntity> tagCache = new ConcurrentHashMap<>();

    // Последнее качество по тегу — чтобы порождать событие при смене GOOD↔BAD.
    private final Map<Long, String> lastQualityByTag = new ConcurrentHashMap<>();

    // Активный эпизод аларма по тегу — для edge-триггера (не слать аларм каждый цикл).
    private final Map<Long, ActiveAlarm> activeAlarmByTag = new ConcurrentHashMap<>();

    /** Активный (ещё не закрытый) аларм по тегу: стабильный alarmId на весь эпизод. */
    private static final class ActiveAlarm {
        final String condition;  // LOW | HIGH
        final String alarmId;    // стабильный идентификатор эпизода
        final String severity;
        final double threshold;

        ActiveAlarm(String condition, String alarmId, String severity, double threshold) {
            this.condition = condition;
            this.alarmId = alarmId;
            this.severity = severity;
            this.threshold = threshold;
        }
    }

    private final com.scada.gateway.kafka.producer.EventProducer eventProducer;

    public OpcUaClientServiceDB(ConfigurationService configurationService,
                                TelemetryRepository telemetryRepository,
                                TelemetryProducer telemetryProducer,
                                AlarmProducer alarmProducer,
                                ModbusClientService modbusClientService,
                                EventLogService eventLogService,
                                com.scada.gateway.kafka.producer.EventProducer eventProducer) {
        this.configurationService = configurationService;
        this.telemetryRepository = telemetryRepository;
        this.telemetryProducer = telemetryProducer;
        this.alarmProducer = alarmProducer;
        this.modbusClientService = modbusClientService;
        this.eventLogService = eventLogService;
        this.eventProducer = eventProducer;
    }

    /**
     * Отметить УСПЕШНОЕ чтение с контроллера. При восстановлении связи (BAD→GOOD)
     * пишем событие в журнал и Kafka, сбрасываем счётчик ошибок.
     */
    private void markControllerUp(ControllerEntity controller) {
        Long id = controller.getId();
        lastGoodReadMs.put(id, System.currentTimeMillis());
        readErrorStreak.put(id, 0);
        Boolean was = connected.put(id, true);
        if (was == null || !was) {
            boolean restored = Boolean.FALSE.equals(was); // was==null → первичное подключение
            String note = restored ? "link restored" : "initial connect";
            log.info("🟢 {}: связь {}", controller.getName(), restored ? "восстановлена" : "установлена");
            eventLogService.logConnection(controller, "CONNECTED", note);
            eventProducer.sendEvent("CONNECTION", "OpcUaClient", "INFO",
                    "Связь с " + controller.getName() + (restored ? " восстановлена" : " установлена"),
                    Map.of("controller", controller.getName(), "state", "CONNECTED"));
        }
    }

    /**
     * Отметить ОШИБКУ связи. Первый переход GOOD→BAD журналируем и шлём событие;
     * дальше только троттлинг лога (раз в N ошибок), чтобы не спамить.
     */
    private void markControllerDown(ControllerEntity controller, String reason) {
        Long id = controller.getId();
        int streak = readErrorStreak.merge(id, 1, Integer::sum);
        Boolean was = connected.put(id, false);
        if (was == null || was) {
            log.warn("🔴 Потеряна связь: {} ({})", controller.getName(), reason);
            eventLogService.logConnection(controller, "DISCONNECTED", reason);
            eventProducer.sendEvent("CONNECTION", "OpcUaClient", "WARNING",
                    "Потеряна связь с " + controller.getName() + ": " + reason,
                    Map.of("controller", controller.getName(), "state", "DISCONNECTED", "reason", reason));
        } else if (streak % 30 == 0) {
            log.warn("🔴 {} всё ещё недоступен ({} ошибок подряд)", controller.getName(), streak);
        }
    }

    @PostConstruct
    public void init() {
        log.info("Initializing Unified Protocol Client Service");
        eventLogService.logSystem("INFO", "SCADA Gateway starting up", Map.of("component", "OpcUaClientService"));

        loadConfiguration();

        List<ControllerEntity> controllers = configurationService.getAllControllers();

        for (ControllerEntity controller : controllers) {
            if (controller.isEnabled()) {
                controllerById.put(controller.getId(), controller);
                connectToController(controller);
            }
        }
    }

    /**
     * СУПЕРВИЗОР СВЯЗИ (авто-переподключение). Каждые 10 c проверяет каждый
     * контроллер и переподнимает OPC UA, если клиент мёртв или давно нет удачных
     * чтений («тихая» смерть сессии). Modbus само-восстанавливается лениво в
     * ModbusClientService, поэтому его здесь только не трогаем. Метод работает и
     * когда контроллер не поднялся при старте (симулятор запустился позже).
     */
    @Scheduled(fixedDelayString = "${gateway.supervise-interval-ms:10000}", initialDelay = 20000)
    public void superviseConnections() {
        long staleMs = 30_000; // нет удачных чтений 30 c → считаем связь мёртвой
        for (ControllerEntity controller : controllerById.values()) {
            if (!controller.isEnabled()) continue;
            String endpoint = controller.getEndpoint();
            boolean isOpc = endpoint != null && endpoint.toLowerCase().contains("opc.tcp");
            if (!isOpc) continue; // Modbus лечится сам

            Long id = controller.getId();
            OpcUaClient client = opcClients.get(id);
            long last = lastGoodReadMs.getOrDefault(id, 0L);
            boolean stale = System.currentTimeMillis() - last > staleMs;
            boolean dead = client == null || stale;

            if (dead && !Boolean.TRUE.equals(reconnecting.get(id))) {
                reconnecting.put(id, true);
                try {
                    log.info("🔄 Супервизор: переподключаю OPC UA {} (client={}, stale={})",
                            controller.getName(), client != null, stale);
                    // «Тихая» смерть OPC UA: чтения зависают, а не бросают исключение,
                    // поэтому poll-цикл мог не вызвать markControllerDown. Фиксируем обрыв
                    // здесь (только если связь ЧИСЛИЛАСЬ живой) — иначе не будет ни события
                    // DISCONNECTED, ни последующего «восстановлена».
                    if (Boolean.TRUE.equals(connected.get(id))) {
                        markControllerDown(controller,
                                "супервизор: нет удачных чтений > " + (staleMs / 1000) + " c");
                    }
                    reconnectOpcUa(controller);
                } catch (Exception e) {
                    log.error("Супервизор: переподключение {} не удалось: {}", controller.getName(), e.getMessage());
                } finally {
                    reconnecting.put(id, false);
                }
            }
        }
    }

    /** Периодическая сводка здоровья в лог (сколько контроллеров на связи). */
    @Scheduled(fixedDelayString = "${gateway.health-log-interval-ms:60000}", initialDelay = 30000)
    public void logHealthSummary() {
        int total = controllerById.size();
        long up = connected.values().stream().filter(Boolean.TRUE::equals).count();
        StringBuilder sb = new StringBuilder();
        for (ControllerEntity c : controllerById.values()) {
            boolean ok = Boolean.TRUE.equals(connected.get(c.getId()));
            sb.append(sb.length() == 0 ? "" : ", ")
              .append(ok ? "🟢 " : "🔴 ").append(c.getName());
        }
        log.info("📋 Здоровье связи: {}/{} на связи [{}]", up, total, sb);
    }

    /** Остановить старый опрос/клиент и заново подключиться к OPC UA-контроллеру. */
    private void reconnectOpcUa(ControllerEntity controller) {
        Long id = controller.getId();
        // 1) гасим старый поток опроса
        runningStatus.put(id, false);
        ExecutorService old = executors.remove(id);
        if (old != null) old.shutdownNow();
        // 2) закрываем старый клиент
        OpcUaClient client = opcClients.remove(id);
        if (client != null) {
            try { client.disconnect().get(); } catch (Exception ignore) {}
        }
        // 3) подключаемся заново тихо (connectOpcUaController сам запустит опрос)
        connectOpcUaController(controller, false);
    }

    private void loadConfiguration() {
        var tags = configurationService.getAllActiveTags();
        tags.forEach(tag -> tagCache.put(tag.getId(), tag));
        log.info("Loaded {} tags", tagCache.size());
        eventLogService.logSystem("INFO", "Configuration loaded", Map.of("tags", tagCache.size(), "controllers", configurationService.getAllControllers().size()));
    }

    private void connectToController(ControllerEntity controller) {
        String endpoint = controller.getEndpoint();
        
        if (endpoint != null && endpoint.toLowerCase().contains("modbus")) {
            log.info("📡 Modbus controller: {} at {}", controller.getName(), endpoint);
            startPollingForController(controller);
        } else if (endpoint != null && endpoint.toLowerCase().contains("opc.tcp")) {
            connectOpcUaController(controller);
        } else {
            log.warn("Unknown protocol for controller: {}", controller.getName());
            eventLogService.logSystem("WARNING", "Unknown protocol for controller: " + controller.getName(), Map.of("controller", controller.getName(), "endpoint", endpoint));
        }
    }

    private void connectOpcUaController(ControllerEntity controller) {
        connectOpcUaController(controller, true);
    }

    /**
     * Подключение к OPC UA-контроллеру. verbose=true — первичное подключение при
     * старте (пишем CONNECTING в журнал, полный лог). verbose=false — супервизорное
     * переподключение (тихо: без CONNECTING-события и без стектрейса каждые 10 c при
     * длительном обрыве). Событие CONNECTED порождает markControllerUp по первому
     * удачному чтению — единый владелец перехода «связь есть», без дублей.
     */
    private void connectOpcUaController(ControllerEntity controller, boolean verbose) {
        try {
            if (verbose) {
                log.info("🔌 Connecting OPC UA: {} at {}", controller.getName(), controller.getEndpoint());
                eventLogService.logConnection(controller, "CONNECTING", null);
            } else {
                log.debug("🔄 Reconnecting OPC UA: {}", controller.getName());
            }

            List<EndpointDescription> endpoints =
                    DiscoveryClient.getEndpoints(controller.getEndpoint()).get();

            if (endpoints.isEmpty()) {
                if (verbose) log.warn("No endpoints found for {}", controller.getEndpoint());
                return;
            }

            EndpointDescription endpoint = endpoints.get(0);

            OpcUaClientConfig config = OpcUaClientConfig.builder()
                    .setApplicationName(LocalizedText.english("SCADA Gateway"))
                    .setApplicationUri("urn:scada:gateway")
                    .setEndpoint(endpoint)
                    .build();

            OpcUaClient client = OpcUaClient.create(config);
            client.connect().get();

            opcClients.put(controller.getId(), client);
            runningStatus.put(controller.getId(), true);
            // Отмечаем момент как «свежий», чтобы супервизор не счёл связь stale в
            // окне между установкой сокета и первым удачным чтением (иначе — цикл
            // переподключений, рвущий только что поднятую сессию).
            lastGoodReadMs.put(controller.getId(), System.currentTimeMillis());

            startPollingForController(controller);

            log.info("✅ OPC UA socket up: {} — опрос запущен", controller.getName());
            // CONNECTED-событие эмитит markControllerUp по первому удачному чтению.

        } catch (Exception e) {
            // Без стектрейса: супервизор повторяет каждые 10 c, факт обрыва уже
            // зафиксирован markControllerDown (DISCONNECTED) с троттлингом.
            String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            if (verbose) {
                log.warn("❌ OPC UA connect failed for {}: {}", controller.getName(), msg);
            } else {
                log.debug("OPC UA reconnect attempt failed for {}: {}", controller.getName(), msg);
            }
        }
    }

    /** Текущий ли это поток опроса своего поколения (защита от «двух опросов»). */
    private boolean isCurrentPoll(Long id, long gen) {
        return runningStatus.getOrDefault(id, false) && gen == pollGeneration.getOrDefault(id, gen);
    }

    private void startPollingForController(ControllerEntity controller) {
        Long id = controller.getId();
        // Новое поколение опроса — этим гасим любой предыдущий поток по этому контроллеру.
        long gen = pollGeneration.merge(id, 1L, Long::sum);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executors.put(id, executor);
        runningStatus.put(id, true);

        String endpoint = controller.getEndpoint();
        boolean isModbus = endpoint != null && endpoint.toLowerCase().contains("modbus");

        if (isModbus) {
            startModbusPolling(controller, executor, gen);
        } else {
            startOpcuaPolling(controller, executor, gen);
        }
    }

    private void startOpcuaPolling(ControllerEntity controller, ExecutorService executor, long gen) {
        final Long cid = controller.getId();
        executor.submit(() -> {
            OpcUaClient client = opcClients.get(cid);

            while (isCurrentPoll(cid, gen)) {
                try {
                    List<TagEntity> tags = configurationService.getTagsForController(controller.getId());
                    // Опрашиваем ВСЕ теги в одном цикле и спим один раз в конце,
                    // а не после каждого тега. Иначе при N тегах период между точками
                    // одного тега = N×pollingRate, и график выглядит редким и «скачет».
                    long cycleDelay = 1000L;
                    int goodReads = 0, badReads = 0;
                    String lastErr = null;

                    for (TagEntity tag : tags) {
                        if (!tag.isEnabled() || !isOpcUaTag(tag)) continue;

                        try {
                            NodeId nodeId = NodeId.parse(tag.getNodeId());
                            DataValue dataValue = client.readValue(0, TimestampsToReturn.Both, nodeId).get();
                            Object val = extractValue(dataValue.getValue());
                            String quality = dataValue.getStatusCode().isGood() ? "GOOD" : "BAD";

                            if (tag.isRecordDevice()) {
                                processDeviceRecord(tag, val, quality, controller);
                            } else {
                                processTagValue(tag, val, quality);
                            }
                            goodReads++;

                        } catch (Exception e) {
                            // Пер-теговый лог НЕ пишем (спам при обрыве). Состояние связи
                            // оценивается на уровне цикла ниже (markControllerUp/Down).
                            badReads++;
                            lastErr = e.getMessage();
                            processTagValue(tag, null, "BAD");
                        }

                        cycleDelay = Math.min(cycleDelay, Math.max(tag.getPollingRate(), 100L));
                    }

                    // Оценка связи по итогам цикла: были удачные чтения → связь есть;
                    // совсем ничего не прочли → контроллер недоступен (супервизор поднимет).
                    // Только текущее поколение опроса правит состояние (иначе «умирающий»
                    // старый поток дёргает DISCONNECTED/CONNECTED — flapping).
                    if (isCurrentPoll(cid, gen)) {
                        if (goodReads > 0) markControllerUp(controller);
                        else if (badReads > 0) markControllerDown(controller,
                                "OPC UA reads failing" + (lastErr != null ? ": " + lastErr : ""));
                    }

                    Thread.sleep(cycleDelay);

                } catch (InterruptedException e) {
                    log.debug("Polling interrupted for {}", controller.getName());
                    break;
                } catch (Exception e) {
                    log.error("Polling error for {}: {}", controller.getName(), e.getMessage());
                    eventLogService.logError("OpcUaClient", "Polling error for " + controller.getName(), e, null, controller);
                }
            }
        });
    }

    private void startModbusPolling(ControllerEntity controller, ExecutorService executor, long gen) {
        String host = extractModbusHost(controller.getEndpoint());
        int port = extractModbusPort(controller.getEndpoint(), 502);
        final Long cid = controller.getId();

        eventLogService.logConnection(controller, "CONNECTING", "Modbus endpoint: " + controller.getEndpoint());

        executor.submit(() -> {
            while (isCurrentPoll(cid, gen)) {
                try {
                    List<TagEntity> tags = configurationService.getTagsForController(controller.getId());
                    long cycleDelay = 1000L;
                    int goodReads = 0, badReads = 0;
                    String lastErr = null;

                    for (TagEntity tag : tags) {
                        if (!tag.isEnabled() || !isModbusTag(tag)) continue;

                        try {
                            Object value = null;

                            if ("FLOAT".equalsIgnoreCase(tag.getDataType())) {
                                value = modbusClientService.readFloat(
                                    host, port,
                                    tag.getModbusAddress(),
                                    tag.getModbusUnitId()
                                );
                            } else if ("INT".equalsIgnoreCase(tag.getDataType()) || "INT16".equalsIgnoreCase(tag.getDataType())) {
                                value = modbusClientService.readInt16(
                                    host, port,
                                    tag.getModbusAddress(),
                                    tag.getModbusUnitId()
                                );
                            } else if ("BOOLEAN".equalsIgnoreCase(tag.getDataType())) {
                                Integer intVal = modbusClientService.readInt16(
                                    host, port,
                                    tag.getModbusAddress(),
                                    tag.getModbusUnitId()
                                );
                                value = intVal != null && intVal != 0;
                            }

                            String quality = value != null ? "GOOD" : "BAD";
                            if (value != null) goodReads++; else badReads++;
                            processTagValue(tag, value, quality);

                        } catch (Exception e) {
                            // Пер-теговый лог не пишем; состояние связи — на уровне цикла.
                            badReads++;
                            lastErr = e.getMessage();
                            processTagValue(tag, null, "BAD");
                        }

                        cycleDelay = Math.min(cycleDelay, Math.max(tag.getPollingRate(), 100L));
                    }

                    // Оценка связи по итогам цикла (Modbus само-восстанавливается лениво).
                    if (isCurrentPoll(cid, gen)) {
                        if (goodReads > 0) markControllerUp(controller);
                        else if (badReads > 0) markControllerDown(controller,
                                "Modbus reads failing" + (lastErr != null ? ": " + lastErr : ""));
                    }

                    Thread.sleep(cycleDelay);

                } catch (InterruptedException e) {
                    log.debug("Modbus polling interrupted for {}", controller.getName());
                    break;
                } catch (Exception e) {
                    log.error("Modbus polling error for {}: {}", controller.getName(), e.getMessage());
                    eventLogService.logError("ModbusClient", "Polling error for " + controller.getName(), e, null, controller);
                }
            }
        });
    }

    private boolean isOpcUaTag(TagEntity tag) {
        return "OPCUA".equalsIgnoreCase(tag.getProtocol()) || 
               (tag.getNodeId() != null && !tag.getNodeId().isEmpty());
    }

    private boolean isModbusTag(TagEntity tag) {
        return "MODBUS".equalsIgnoreCase(tag.getProtocol()) || 
               (tag.getModbusAddress() != null && tag.getModbusAddress() > 0);
    }

    private final ObjectMapper recordMapper = new ObjectMapper();

    /**
     * Разбор СЫРОЙ ЗАПИСИ ПРИБОРА (роль реального драйвера PAC_easy_drv_LZ).
     * Значение record — строка вида "ИМЯ={ПОЛЕ=знач, ПОЛЕ=знач, ...}". Метод:
     *   1) вырезает содержимое {...} и разбивает на пары поле=значение;
     *   2) по карте fieldsJson находит для поля channelId (node.id базы) и тип;
     *   3) приводит значение к типу и шлёт КАЖДОЕ поле в Kafka отдельным каналом
     *      (tagId = node.id), device/field/type — в metadata.
     */
    private void processDeviceRecord(TagEntity tag, Object record, String quality, ControllerEntity controller) {
        if (record == null || tag.getFieldsJson() == null) return;
        String s = record.toString();
        int lb = s.indexOf('{'), rb = s.lastIndexOf('}');
        if (lb < 0 || rb <= lb) {
            log.warn("Запись прибора {} без {{}}: {}", tag.getDeviceName(), s);
            return;
        }
        // Разбираем пары поле=значение из содержимого { ... }.
        Map<String, String> parsed = new HashMap<>();
        for (String part : s.substring(lb + 1, rb).split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0) parsed.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        // Карта полей: [{"name":"ST","channelId":385,"dataType":"BOOLEAN"},…].
        List<Map<String, Object>> fields;
        try {
            fields = recordMapper.readValue(tag.getFieldsJson(), List.class);
        } catch (Exception e) {
            log.error("fieldsJson прибора {} не разобран: {}", tag.getDeviceName(), e.getMessage());
            return;
        }
        Long ctrlId = controller != null ? controller.getId() : null;
        for (Map<String, Object> f : fields) {
            String field = (String) f.get("name");
            String raw = parsed.get(field);
            if (raw == null) continue;
            String dt = String.valueOf(f.get("dataType"));
            Object value;
            try {
                value = "BOOLEAN".equalsIgnoreCase(dt)
                        ? (!"0".equals(raw) && !raw.isEmpty())
                        : Double.parseDouble(raw);
            } catch (NumberFormatException nfe) {
                continue;
            }
            Long channelId = f.get("channelId") == null ? null : ((Number) f.get("channelId")).longValue();
            String channelName = tag.getDeviceName() + "." + field;
            telemetryProducer.sendFieldTelemetry(channelId, channelName, value, quality,
                    tag.getDeviceName(), field, tag.getDeviceType(), ctrlId);
        }
    }

    @Transactional
    private void processTagValue(TagEntity tag, Object value, String quality) {
        // Чтение тега НЕ логируем в event_log на каждый опрос (это был шум:
        // строка в БД на каждую точку). Значения идут в telemetry + Kafka;
        // в журнал событий пишем только смену качества и ошибки.

        // Событие при смене качества сигнала (GOOD↔BAD) — попадает в журнал событий.
        String prevQuality = lastQualityByTag.put(tag.getId(), quality);
        if (prevQuality != null && !prevQuality.equals(quality)) {
            String sev = "GOOD".equalsIgnoreCase(quality) ? "INFO" : "WARNING";
            Map<String, Object> details = new HashMap<>();
            details.put("tagId", tag.getId());
            details.put("tagName", tag.getName());
            details.put("from", prevQuality);
            details.put("to", quality);
            eventLogService.logEvent("QUALITY_CHANGE", "OpcUaClient", sev,
                    String.format("Quality changed for %s: %s → %s", tag.getName(), prevQuality, quality),
                    details);
        }
        
        // Проверка на алармы (edge-триггер, см. evaluateAlarms).
        if (value instanceof Number) {
            evaluateAlarms(tag, ((Number) value).doubleValue());
        }

        saveTelemetry(tag, value, quality);

        if (value != null) {
            telemetryProducer.sendTelemetry(tag, value, quality);
            // per-tag на каждый опрос — только debug (иначе поток INFO на 2471 тег/цикл).
            log.debug("📊 {} = {}", tag.getName(), value);
        } else {
            // NULL при обрыве — не спамим на каждый тег; состояние связи фиксирует
            // markControllerDown на уровне цикла опроса.
            log.debug("⚠️ {} = NULL (quality: {})", tag.getName(), quality);
        }
    }

    /**
     * Edge-триггер алармов. Аларм публикуется в Kafka ОДИН раз при выходе
     * значения за предел и ещё раз (cleared=true) при возврате в норму.
     * Пока значение остаётся вне предела — повторные алармы НЕ шлются, что
     * убирает «потоп» одинаковых алармов каждую секунду. Гистерезис (deadband)
     * гасит дребезг у самой границы.
     *
     * Severity дифференцируем: ниже min → MINOR (сильно ниже → MAJOR),
     * выше max → MAJOR (сильно выше → CRITICAL).
     */
    private void evaluateAlarms(TagEntity tag, double numValue) {
        Double min = tag.getMinValue();
        Double max = tag.getMaxValue();
        if (min == null && max == null) {
            return;
        }

        String unit = tag.getUnit() != null ? tag.getUnit() : "";
        double range = (min != null && max != null)
                ? Math.max(max - min, 0.0001)
                : Math.max(Math.abs(numValue), 1.0);
        double deadband = 0.02 * range; // 2% диапазона — анти-дребезг на возврате в норму

        ActiveAlarm active = activeAlarmByTag.get(tag.getId());

        String condition = null; // HIGH | LOW | null(норма)
        String severity = null;
        double threshold = 0;
        String message = null;

        if (max != null && numValue > max) {
            condition = "HIGH";
            severity = (numValue > max + 0.3 * range) ? "CRITICAL" : "MAJOR";
            threshold = max;
            message = String.format("High value: %.2f > %.2f %s", numValue, max, unit);
        } else if (min != null && numValue < min) {
            condition = "LOW";
            severity = (numValue < min - 0.3 * range) ? "MAJOR" : "MINOR";
            threshold = min;
            message = String.format("Low value: %.2f < %.2f %s", numValue, min, unit);
        }

        if (condition != null) {
            // В зоне аларма: шлём только при НОВОМ эпизоде или смене направления
            // нарушения (LOW↔HIGH); пока то же нарушение — молчим (анти-флуд).
            if (active == null || !active.condition.equals(condition)) {
                if (active != null) {
                    sendClear(tag, active, numValue); // закрываем прежнее нарушение другого знака
                }
                String alarmId = "ALARM_" + tag.getId() + "_" + condition + "_" + System.currentTimeMillis();
                eventLogService.logAlarm(tag, severity, message, threshold, numValue);
                alarmProducer.sendAlarm(tag, alarmId, severity, message, threshold, numValue, false);
                activeAlarmByTag.put(tag.getId(), new ActiveAlarm(condition, alarmId, severity, threshold));
            }
            return;
        }

        // Значение в норме: если был активный аларм и вернулись в норму с
        // запасом (deadband) — закрываем эпизод (cleared=true) ровно один раз.
        if (active != null) {
            boolean backToNormal =
                    (min == null || numValue >= min + deadband) &&
                    (max == null || numValue <= max - deadband);
            if (backToNormal) {
                sendClear(tag, active, numValue);
                activeAlarmByTag.remove(tag.getId());
            }
        }
    }

    private void sendClear(TagEntity tag, ActiveAlarm active, double numValue) {
        String message = String.format("Cleared: %.2f back to normal", numValue);
        alarmProducer.sendAlarm(tag, active.alarmId, active.severity, message, active.threshold, numValue, true);
        eventLogService.logEvent("ALARM_CLEARED", "OpcUaClient", "INFO",
                String.format("Alarm cleared for %s (%.2f)", tag.getName(), numValue),
                Map.of("tagId", tag.getId(), "tagName", tag.getName(), "alarmId", active.alarmId));
    }

    /**
     * Запись значения в тег ПЛК по OPC UA (команда управления от Monitor Srv).
     * Возвращает исход для отправки результата обратно в Monitor.
     */
    public CommandOutcome writeTag(Long tagId, Object value, String dataType) {
        TagEntity tag = tagCache.get(tagId);
        if (tag == null) {
            return new CommandOutcome(false, "REJECTED", "Тег не найден: " + tagId, null);
        }
        if (!isOpcUaTag(tag)) {
            return new CommandOutcome(false, "REJECTED", "Запись поддержана только для OPC UA тегов", null);
        }

        Long controllerId = tag.getController() != null ? tag.getController().getId() : null;
        OpcUaClient client = controllerId != null ? opcClients.get(controllerId) : null;
        if (client == null) {
            return new CommandOutcome(false, "FAILED", "Контроллер не подключён", null);
        }

        try {
            NodeId nodeId = NodeId.parse(tag.getNodeId());
            String dt = dataType != null ? dataType : tag.getDataType();
            Variant variant = toVariant(dt, value);

            // status/time = null: их проставляет сервер (канон milo для записи).
            DataValue dataValue = new DataValue(variant, null, null);
            StatusCode status = client.writeValue(nodeId, dataValue).get();

            if (status.isGood()) {
                log.info("✍ Записано {} = {} (tag {})", tag.getName(), variant.getValue(), tagId);
                eventLogService.logEvent("COMMAND_APPLIED", "OpcUaClient", "INFO",
                        String.format("Записано %s = %s", tag.getName(), value),
                        Map.of("tagId", tagId, "value", String.valueOf(value)));
                return new CommandOutcome(true, "APPLIED", "Записано значение " + value, value);
            }
            return new CommandOutcome(false, "REJECTED", "OPC UA отклонил запись: " + status, null);

        } catch (Exception e) {
            log.error("Ошибка записи тега {}: {}", tagId, e.getMessage());
            eventLogService.logError("OpcUaClient", "Ошибка записи тега " + tag.getName(), e, tag, null);
            return new CommandOutcome(false, "FAILED", "Ошибка записи: " + e.getMessage(), null);
        }
    }

    /** Конвертация значения команды в OPC UA Variant нужного типа. */
    private Variant toVariant(String dataType, Object value) {
        String dt = dataType == null ? "" : dataType.trim().toUpperCase();
        if (dt.startsWith("BOOL")) return new Variant(toBool(value));
        if (dt.startsWith("INT"))  return new Variant(toInt(value));
        if (dt.startsWith("FLOAT") || dt.startsWith("REAL")) return new Variant(toFloat(value));
        if (dt.startsWith("DOUBLE")) return new Variant(toDouble(value));
        return new Variant(value);
    }

    private Boolean toBool(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0.0;
        return Boolean.parseBoolean(String.valueOf(v).trim());
    }

    private Integer toInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(String.valueOf(v).trim());
    }

    private Float toFloat(Object v) {
        if (v instanceof Number n) return n.floatValue();
        return Float.parseFloat(String.valueOf(v).trim());
    }

    private Double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        return Double.parseDouble(String.valueOf(v).trim());
    }

    /** Исход записи тега для формирования CommandResultMessage. */
    public static final class CommandOutcome {
        public final boolean success;
        public final String status;
        public final String message;
        public final Object appliedValue;

        public CommandOutcome(boolean success, String status, String message, Object appliedValue) {
            this.success = success;
            this.status = status;
            this.message = message;
            this.appliedValue = appliedValue;
        }
    }

    private void saveTelemetry(TagEntity tag, Object value, String quality) {
        try {
            TelemetryEntity t = new TelemetryEntity();
            t.setTagId(tag.getId());
            t.setTime(Instant.now());
            t.setQuality(quality);

            if (value instanceof Number) {
                t.setValue(((Number) value).doubleValue());
            } else if (value instanceof Boolean) {
                t.setValue(((Boolean) value) ? 1.0 : 0.0);
            } else if (value != null) {
                t.setValueString(value.toString());
            }

            telemetryRepository.save(t);
        } catch (Exception e) {
            log.error("DB save error: {}", e.getMessage());
            eventLogService.logError("Database", "Failed to save telemetry for tag " + tag.getName(), e, tag, null);
        }
    }

    private Object extractValue(Variant variant) {
        if (variant == null || variant.isNull()) return null;

        Object v = variant.getValue();

        if (v instanceof UInteger) {
            return ((UInteger) v).longValue();
        }

        return v;
    }

    private String extractModbusHost(String endpoint) {
        try {
            String s = endpoint.replace("modbus://", "");
            if (s.contains(":")) {
                return s.split(":")[0];
            }
            return s;
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    private int extractModbusPort(String endpoint, int defaultPort) {
        try {
            String s = endpoint.replace("modbus://", "");
            String[] parts = s.split(":");
            if (parts.length > 1) {
                return Integer.parseInt(parts[1]);
            }
            return defaultPort;
        } catch (Exception e) {
            return defaultPort;
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down all connections...");
        eventLogService.logSystem("INFO", "SCADA Gateway shutting down", Map.of("component", "OpcUaClientService"));

        for (Long id : runningStatus.keySet()) {
            runningStatus.put(id, false);
        }

        for (ExecutorService executor : executors.values()) {
            if (executor != null) {
                executor.shutdown();
            }
        }

        for (Map.Entry<Long, OpcUaClient> entry : opcClients.entrySet()) {
            if (entry.getValue() != null) {
                try {
                    entry.getValue().disconnect().get();
                    log.info("Disconnected OPC UA client for controller {}", entry.getKey());
                } catch (Exception ignored) {}
            }
        }

        modbusClientService.disconnectAll();
        log.info("Shutdown complete");
    }
}
