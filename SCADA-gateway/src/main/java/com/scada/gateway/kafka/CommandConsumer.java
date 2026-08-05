package com.scada.gateway.kafka;

import com.scada.gateway.kafka.dto.CommandMessage;
import com.scada.gateway.kafka.dto.CommandResultMessage;
import com.scada.gateway.kafka.producer.CommandResultProducer;
import com.scada.gateway.opcua.OpcUaClientServiceDB;
import com.scada.gateway.opcua.OpcUaClientServiceDB.CommandOutcome;
import com.scada.gateway.service.EventLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Принимает команды управления из Monitor Srv (топик scada-commands),
 * пишет значение в ПЛК по OPC UA и публикует результат обратно
 * (топик scada-command-results).
 */
@Component
public class CommandConsumer {

    private static final Logger log = LoggerFactory.getLogger(CommandConsumer.class);

    private final OpcUaClientServiceDB opcUaClientService;
    private final CommandResultProducer resultProducer;
    private final EventLogService eventLogService;

    public CommandConsumer(OpcUaClientServiceDB opcUaClientService,
                           CommandResultProducer resultProducer,
                           EventLogService eventLogService) {
        this.opcUaClientService = opcUaClientService;
        this.resultProducer = resultProducer;
        this.eventLogService = eventLogService;
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

        log.info("← команда: tag={} ({}), value={}, by={}",
                cmd.getTagName(), cmd.getTagId(), cmd.getValue(), cmd.getRequestedBy());

        CommandOutcome outcome = hasId
                ? opcUaClientService.writeTag(cmd.getTagId(), cmd.getValue(), cmd.getDataType())
                : opcUaClientService.writeTagByName(cmd.getTagName(), cmd.getValue(), cmd.getDataType());

        CommandResultMessage result = new CommandResultMessage();
        result.setCommandId(cmd.getCommandId());
        result.setTagId(cmd.getTagId());
        result.setTagName(cmd.getTagName());
        result.setStatus(outcome.status);
        result.setSuccess(outcome.success);
        result.setMessage(outcome.message);
        result.setAppliedValue(outcome.appliedValue);
        result.setTimestamp(Instant.now());
        resultProducer.send(result);

        Map<String, Object> details = new HashMap<>();
        details.put("tagId", cmd.getTagId());
        details.put("value", String.valueOf(cmd.getValue()));
        details.put("requestedBy", cmd.getRequestedBy());
        details.put("status", outcome.status);
        eventLogService.logEvent("COMMAND", "CommandConsumer",
                outcome.success ? "INFO" : "WARNING",
                String.format("Команда %s = %s от %s: %s",
                        cmd.getTagName(), cmd.getValue(), cmd.getRequestedBy(), outcome.status),
                details);
    }
}
