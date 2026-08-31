package com.scada.gateway.command;

import com.scada.gateway.modbus.ModbusClientService;
import com.scada.gateway.modbus.ModbusEndpoint;
import com.scada.gateway.model.TagProtocols;
import com.scada.gateway.model.entity.ControllerEntity;
import com.scada.gateway.model.entity.TagEntity;
import com.scada.gateway.opcua.ValueCodec;
import com.scada.gateway.service.EventLogService;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Команды записи в ПЛК (OPC UA / Modbus) от Monitor Srv.
 *
 * <p>Выделено из god-класса OpcUaClientServiceDB (шаг 2d декомпозиции). Живыми картами
 * (кэш тегов, OPC UA-клиенты) по-прежнему владеет god-класс — сюда они приходят через
 * УЗКИЕ порты {@link TagCatalog} и {@link OpcUaClientRegistry} (инверсия зависимостей):
 * сервис команд зависит от интерфейсов, а не от god-класса, и в тестах порты
 * подменяются моками. Логика записи перенесена один-в-один.
 */
@Service
public class CommandService {

    private static final Logger log = LoggerFactory.getLogger(CommandService.class);

    private final TagCatalog tagCatalog;
    private final OpcUaClientRegistry opcUaClients;
    private final ModbusClientService modbus;
    private final EventLogService eventLog;
    private final long opcuaOpTimeoutMs;

    public CommandService(TagCatalog tagCatalog,
                          OpcUaClientRegistry opcUaClients,
                          ModbusClientService modbus,
                          EventLogService eventLog,
                          @Value("${gateway.opcua-op-timeout-ms:5000}") long opcuaOpTimeoutMs) {
        this.tagCatalog = tagCatalog;
        this.opcUaClients = opcUaClients;
        this.modbus = modbus;
        this.eventLog = eventLog;
        this.opcuaOpTimeoutMs = opcuaOpTimeoutMs;
    }

    /**
     * Запись значения в тег ПЛК (команда управления от Monitor Srv). Возвращает исход
     * для отправки результата обратно в Monitor.
     */
    public CommandOutcome writeTag(Long tagId, Object value, String dataType) {
        TagEntity tag = tagCatalog.byId(tagId);
        if (tag == null) {
            return new CommandOutcome(false, CommandStatus.REJECTED_UNKNOWN_TAG, "Тег не найден: " + tagId, null);
        }
        // Доступ к записи — как у реального ПЛК: показание датчика (давление, расход)
        // изменить нельзя, только команду актуатора (клапан/мотор/DO). Отклоняем ДО
        // похода в контроллер: для OPC UA это экономит round-trip до Bad_NotWritable,
        // для Modbus — ЕДИНСТВЕННАЯ защита (у holding-регистра нет признака «только
        // чтение», без этой проверки регистр датчика молча перезапишется).
        if (!tag.isWritable()) {
            return new CommandOutcome(false, CommandStatus.REJECTED_NOT_WRITABLE,
                    "Тег только для чтения (датчик), запись запрещена: " + tag.getName(), null);
        }
        // Маршрутизация по протоколу — деталь реализации шлюза, наружу не торчит (A6).
        // Запись реализована для OPC UA и Modbus; иной протокол → PROTOCOL_UNSUPPORTED.
        if (TagProtocols.isOpcUaTag(tag)) {
            return writeOpcUa(tag, value, dataType);
        }
        if (TagProtocols.isModbusTag(tag)) {
            return writeModbus(tag, value, dataType);
        }
        String proto = tag.getProtocol() != null ? tag.getProtocol() : "неизвестный";
        return new CommandOutcome(false, CommandStatus.REJECTED_PROTOCOL_UNSUPPORTED,
                "Запись не реализована для протокола: " + proto, null);
    }

    /**
     * Запись значения по ИМЕНИ канала (полному пути узла). Так тег адресует
     * scada-editor runtime: имя канала — это и Kafka-key телеметрии, и tag_id
     * в редакторе, поэтому внешнему монитору не нужна нумерация тегов шлюза.
     */
    public CommandOutcome writeTagByName(String tagName, Object value, String dataType) {
        TagEntity tag = tagCatalog.byName(tagName);
        if (tag == null) {
            return new CommandOutcome(false, CommandStatus.REJECTED_UNKNOWN_TAG, "Тег не найден по имени: " + tagName, null);
        }
        return writeTag(tag.getId(), value, dataType);
    }

    /** Запись по OPC UA. */
    private CommandOutcome writeOpcUa(TagEntity tag, Object value, String dataType) {
        Long controllerId = tag.getController() != null ? tag.getController().getId() : null;
        OpcUaClient client = opcUaClients.forController(controllerId);
        if (client == null) {
            return new CommandOutcome(false, CommandStatus.FAILED_NO_CONNECTION, "Контроллер не подключён", null);
        }

        // Приведение типа — ОТДЕЛЬНО от записи: ошибка конвертации значения к типу тега
        // это ошибка данных/конфигурации (REJECTED_TYPE_MISMATCH), а не сбой связи.
        NodeId nodeId;
        Variant variant;
        try {
            nodeId = NodeId.parse(tag.getNodeId());
            String dt = dataType != null ? dataType : tag.getDataType();
            variant = ValueCodec.toVariant(dt, value);
        } catch (Exception e) {
            log.warn("Значение '{}' не приводится к типу тега {}: {}", value, tag.getName(), e.getMessage());
            return new CommandOutcome(false, CommandStatus.REJECTED_TYPE_MISMATCH,
                    "Значение не приводится к типу тега: " + e.getMessage(), null);
        }

        try {
            // status/time = null: их проставляет сервер (канон milo для записи).
            DataValue dataValue = new DataValue(variant, null, null);
            // Таймаут: без него зависшая запись вешает поток консьюмера команд навсегда.
            StatusCode status = client.writeValue(nodeId, dataValue).get(opcuaOpTimeoutMs, TimeUnit.MILLISECONDS);

            if (status.isGood()) {
                log.info("✍ OPC UA записано {} = {} (tag {})", tag.getName(), variant.getValue(), tag.getId());
                eventLog.logEvent("COMMAND_APPLIED", "OpcUaClient", "INFO",
                        String.format("Записано %s = %s", tag.getName(), value),
                        Map.of("tagId", tag.getId(), "value", String.valueOf(value)));
                return new CommandOutcome(true, CommandStatus.APPLIED, "Записано значение " + value, value);
            }
            // Разбор неудачного StatusCode на осмысленные для оператора исходы.
            return new CommandOutcome(false, CommandStatusClassifier.classify(status),
                    "OPC UA отклонил запись: " + status, null);

        } catch (Exception e) {
            log.error("Ошибка записи тега {}: {}", tag.getName(), e.getMessage());
            eventLog.logError("OpcUaClient", "Ошибка записи тега " + tag.getName(), e, tag, null);
            return new CommandOutcome(false, CommandStatus.FAILED_WRITE, "Ошибка записи: " + e.getMessage(), null);
        }
    }

    /**
     * A6: запись по Modbus. Симметрична чтению (holding-регистры, адрес −40001,
     * FLOAT little-endian по словам). BOOLEAN/INT → один регистр (FC06),
     * FLOAT → два регистра (FC16). У Modbus нет понятия sourceTime и «not writable»
     * на уровне узла, поэтому исходы грубее OPC UA: TYPE_MISMATCH / NO_CONNECTION /
     * WRITE.
     */
    private CommandOutcome writeModbus(TagEntity tag, Object value, String dataType) {
        ControllerEntity ctrl = tag.getController();
        if (ctrl == null || ctrl.getEndpoint() == null) {
            return new CommandOutcome(false, CommandStatus.FAILED_NO_CONNECTION, "Контроллер не задан", null);
        }
        if (tag.getModbusAddress() == null) {
            return new CommandOutcome(false, CommandStatus.REJECTED_UNKNOWN_TAG, "У тега нет Modbus-адреса", null);
        }
        String host = ModbusEndpoint.host(ctrl.getEndpoint());
        int port = ModbusEndpoint.port(ctrl.getEndpoint(), 502);
        int addr = tag.getModbusAddress();
        int unitId = tag.getModbusUnitId();
        String dt = dataType != null ? dataType : tag.getDataType();

        try {
            if ("FLOAT".equalsIgnoreCase(dt)) {
                modbus.writeFloat(host, port, addr, unitId, ValueCodec.toFloat(value));
            } else if ("INT".equalsIgnoreCase(dt) || "INT16".equalsIgnoreCase(dt)) {
                modbus.writeRegister(host, port, addr, unitId, ValueCodec.toInt(value) & 0xFFFF);
            } else if ("BOOLEAN".equalsIgnoreCase(dt)) {
                modbus.writeRegister(host, port, addr, unitId, ValueCodec.toBool(value) ? 1 : 0);
            } else {
                return new CommandOutcome(false, CommandStatus.REJECTED_TYPE_MISMATCH,
                        "Неизвестный тип Modbus-тега: " + dt, null);
            }
        } catch (NumberFormatException | ClassCastException e) {
            return new CommandOutcome(false, CommandStatus.REJECTED_TYPE_MISMATCH,
                    "Значение не приводится к типу тега: " + e.getMessage(), null);
        } catch (java.io.IOException e) {
            return new CommandOutcome(false, CommandStatus.FAILED_NO_CONNECTION,
                    "Нет связи с контроллером: " + e.getMessage(), null);
        } catch (Exception e) {
            log.error("Ошибка записи Modbus-тега {}: {}", tag.getName(), e.getMessage());
            eventLog.logError("ModbusClient", "Ошибка записи тега " + tag.getName(), e, tag, null);
            return new CommandOutcome(false, CommandStatus.FAILED_WRITE, "Ошибка записи Modbus: " + e.getMessage(), null);
        }

        log.info("✍ Modbus записано {} = {} (addr {})", tag.getName(), value, addr);
        eventLog.logEvent("COMMAND_APPLIED", "ModbusClient", "INFO",
                String.format("Записано %s = %s", tag.getName(), value),
                Map.of("tagName", tag.getName(), "value", String.valueOf(value)));
        return new CommandOutcome(true, CommandStatus.APPLIED, "Записано значение " + value, value);
    }
}
