package com.scada.gateway.ha;

import com.scada.gateway.command.CommandOutcome;
import com.scada.gateway.command.CommandService;
import com.scada.gateway.command.CommandStatus;
import com.scada.gateway.kafka.CommandConsumer;
import com.scada.gateway.kafka.dto.CommandMessage;
import com.scada.gateway.kafka.dto.CommandResultMessage;
import com.scada.gateway.kafka.producer.CommandResultProducer;
import com.scada.gateway.kafka.producer.EventProducer;
import com.scada.gateway.kafka.producer.TelemetryProducer;
import com.scada.gateway.model.entity.TagEntity;
import com.scada.gateway.service.EventLogService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Горячий резерв без брокера: резервный экземпляр наружу молчит, консьюмер команд следует
 * за ролью, устаревшие команды в ПЛК не уходят. Выборы на настоящей Kafka — {@link LeaderElectorIT}.
 */
class HotStandbyTest {

    /** Роль, которую тест переключает сам. */
    private static final class Role implements Leadership {
        volatile boolean active;
        @Override public boolean isActive() { return active; }
        @Override public boolean isHaEnabled() { return true; }
        @Override public String instanceId() { return "test"; }
    }

    private static TagEntity tag() {
        TagEntity t = new TagEntity();
        t.setId(1L);
        t.setName("Барановичи-1.BN1_MCA1.V_ST_1.LINE1V0.ST");
        return t;
    }

    @SuppressWarnings("unchecked")
    private static KafkaTemplate<String, Object> kafka() {
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), any())).thenReturn(new CompletableFuture<>());
        return kafka;
    }

    @Test
    @DisplayName("Резервный не публикует телеметрию и события; ставший активным — публикует")
    void standbyStaysSilentInKafka() {
        Role role = new Role();
        KafkaTemplate<String, Object> kafka = kafka();
        TelemetryProducer telemetry = new TelemetryProducer(kafka, "scada.tags", mock(EventLogService.class), role);
        EventProducer events = new EventProducer(kafka, "scada-events", role);
        ReflectionTestUtils.setField(telemetry, "kafkaEnabled", true);
        ReflectionTestUtils.setField(events, "kafkaEnabled", true);
        ReflectionTestUtils.setField(events, "publishEvents", true);

        telemetry.sendTelemetry(tag(), 1, "GOOD", Instant.now());
        telemetry.sendFieldTelemetry("LINE1V0.ST", 1, "GOOD", Instant.now());
        events.sendEvent("CONNECTION", "OpcUaClient", "INFO", "связь", Map.of());
        verifyNoInteractions(kafka);

        role.active = true;
        telemetry.sendTelemetry(tag(), 1, "GOOD", Instant.now());
        events.sendEvent("CONNECTION", "OpcUaClient", "INFO", "связь", Map.of());
        verify(kafka).send(eq("scada.tags"), anyString(), any());
        verify(kafka).send(eq("scada-events"), anyString(), any());
    }

    private CommandConsumer consumer(CommandService service, CommandResultProducer results) {
        return new CommandConsumer(service, results, mock(EventLogService.class), new SimpleMeterRegistry());
    }

    private static CommandMessage command() {
        CommandMessage cmd = new CommandMessage();
        cmd.setCommandId(java.util.UUID.randomUUID().toString());
        cmd.setTagName(tag().getName());
        cmd.setValue(1);
        return cmd;
    }

    @Test
    @DisplayName("Команда, пролежавшая в топике дольше max-age, в ПЛК не уходит: REJECTED_EXPIRED")
    void expiredCommandIsNotWritten() {
        CommandService service = mock(CommandService.class);
        CommandResultProducer results = mock(CommandResultProducer.class);

        consumer(service, results).onCommand(command(), System.currentTimeMillis() - 10 * 60_000);

        verify(service, never()).writeTagByName(anyString(), any(), any());
        ArgumentCaptor<CommandResultMessage> result = ArgumentCaptor.forClass(CommandResultMessage.class);
        verify(results).send(result.capture());
        assertThat(result.getValue().getStatus()).isEqualTo(CommandStatus.REJECTED_EXPIRED.name());
    }

    @Test
    @DisplayName("Свежая команда (например, пришедшая за время переключения пары) исполняется")
    void freshCommandIsWritten() {
        CommandService service = mock(CommandService.class);
        when(service.writeTagByName(anyString(), any(), any()))
                .thenReturn(new CommandOutcome(true, CommandStatus.APPLIED, "ok", 1));

        consumer(service, mock(CommandResultProducer.class)).onCommand(command(), System.currentTimeMillis() - 3000);

        verify(service).writeTagByName(anyString(), any(), any());
    }

    @Test
    @DisplayName("Возраст команды: по метке записи Kafka, иначе по полю timestamp, иначе неизвестен")
    void commandAgeSources() {
        CommandMessage cmd = command();
        assertThat(CommandConsumer.ageMs(cmd, null)).isNull();
        cmd.setTimestamp(Instant.now().minusSeconds(60));
        assertThat(CommandConsumer.ageMs(cmd, null)).isBetween(59_000L, 70_000L);
        assertThat(CommandConsumer.ageMs(cmd, System.currentTimeMillis() - 1000)).isBetween(1000L, 5_000L);
    }

    @Test
    @DisplayName("Консьюмер команд включается с лидерством и выключается при уходе в резерв")
    void commandListenerFollowsRole() {
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(registry.getListenerContainer(CommandConsumer.LISTENER_ID)).thenReturn(container);
        CommandListenerSwitch sw = new CommandListenerSwitch(registry);

        when(container.isRunning()).thenReturn(false);
        sw.onLeadershipChanged(new LeadershipChanged(true, "a", "получено лидерство"));
        verify(container).start();

        when(container.isRunning()).thenReturn(true);
        sw.onLeadershipChanged(new LeadershipChanged(false, "a", "лидерство передано"));
        verify(container).stop(any(Runnable.class));
    }
}
