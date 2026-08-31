package com.scada.gateway.kafka;

import com.scada.gateway.kafka.dto.CommandMessage;
import com.scada.gateway.kafka.dto.CommandResultMessage;
import com.scada.gateway.kafka.producer.CommandResultProducer;
import com.scada.gateway.command.CommandOutcome;
import com.scada.gateway.command.CommandService;
import com.scada.gateway.service.EventLogService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Принимает команды управления из Monitor Srv (топик scada-commands),
 * пишет значение в ПЛК по OPC UA и публикует результат обратно
 * (топик scada-command-results).
 */
@Component
public class CommandConsumer {

    private static final Logger log = LoggerFactory.getLogger(CommandConsumer.class);

    private final CommandService commandService;
    private final CommandResultProducer resultProducer;
    private final EventLogService eventLogService;
    private final MeterRegistry meterRegistry;

    // A7: окно недавних commandId для идемпотентности — Kafka at-least-once или двойная
    // доставка одной команды не должна писать в ПЛК дважды. Держим до DEDUP_MAX последних
    // id с TTL DEDUP_TTL_MS (команд единицы/мин — контеншена на synchronized-map нет).
    private static final int DEDUP_MAX = 1000;
    private static final long DEDUP_TTL_MS = 60_000;
    private final Map<String, Long> recentCommands = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, false) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > DEDUP_MAX;
                }
            });

    public CommandConsumer(CommandService commandService,
                           CommandResultProducer resultProducer,
                           EventLogService eventLogService,
                           MeterRegistry meterRegistry) {
        this.commandService = commandService;
        this.resultProducer = resultProducer;
        this.eventLogService = eventLogService;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
            topics = "${kafka.topics.commands:scada-commands}",
            groupId = "${spring.kafka.consumer.group-id:scada-gateway-group}",
            containerFactory = "commandKafkaListenerContainerFactory"
    )
    public void onCommand(CommandMessage cmd) {
        // Тег адресуется либо внутренним id (Monitor Srv), либо именем канала —
        // полным путём узла (scada-editor runtime). Имя самодостаточно: оно же
        // Kafka-key телеметрии, поэтому отправителю не нужно знать нумерацию шлюза.
        boolean hasId   = cmd != null && cmd.getTagId() != null;
        boolean hasName = cmd != null && cmd.getTagName() != null && !cmd.getTagName().isBlank();
        if (!hasId && !hasName) {
            log.warn("Получена команда без tagId и tagName, пропускаем");
            return;
        }

        // A7: дубль (повторная доставка того же commandId) — не пишем в ПЛК ещё раз.
        if (isDuplicate(cmd.getCommandId())) {
            log.warn("⏭ Команда {} уже обработана недавно — пропуск (идемпотентность)", cmd.getCommandId());
            return;
        }

        log.info("← команда: tag={} ({}), value={}, by={}",
                cmd.getTagName(), cmd.getTagId(), cmd.getValue(), cmd.getRequestedBy());

        CommandOutcome outcome = hasId
                ? commandService.writeTag(cmd.getTagId(), cmd.getValue(), cmd.getDataType())
                : commandService.writeTagByName(cmd.getTagName(), cmd.getValue(), cmd.getDataType());

        // Метрика: сколько команд и с каким исходом (APPLIED/REJECTED_*/FAILED_*).
        meterRegistry.counter("scada.commands.total", "status", outcome.status.name()).increment();

        CommandResultMessage result = new CommandResultMessage();
        result.setCommandId(cmd.getCommandId());
        result.setTagId(cmd.getTagId());
        result.setTagName(cmd.getTagName());
        result.setStatus(outcome.status.name());
        result.setSuccess(outcome.success);
        result.setMessage(outcome.message);
        result.setAppliedValue(outcome.appliedValue);
        result.setTimestamp(Instant.now());
        resultProducer.send(result);

        Map<String, Object> details = new HashMap<>();
        details.put("tagId", cmd.getTagId());
        details.put("value", String.valueOf(cmd.getValue()));
        details.put("requestedBy", cmd.getRequestedBy());
        details.put("status", outcome.status.name());
        eventLogService.logEvent("COMMAND", "CommandConsumer",
                outcome.success ? "INFO" : "WARNING",
                String.format("Команда %s = %s от %s: %s",
                        cmd.getTagName(), cmd.getValue(), cmd.getRequestedBy(), outcome.status),
                details);
    }

    /** true, если commandId уже применялся в пределах TTL (дубль). Иначе запоминает его. */
    private boolean isDuplicate(String commandId) {
        if (commandId == null || commandId.isBlank()) return false;
        long now = System.currentTimeMillis();
        Long prev = recentCommands.get(commandId);
        if (prev != null && now - prev < DEDUP_TTL_MS) {
            return true;
        }
        recentCommands.put(commandId, now);
        return false;
    }
}
