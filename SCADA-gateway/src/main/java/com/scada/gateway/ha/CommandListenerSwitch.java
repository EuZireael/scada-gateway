package com.scada.gateway.ha;

import com.scada.gateway.kafka.CommandConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Команды исполняет только активный экземпляр: консьюмер {@code scada-commands} создаётся
 * выключенным ({@code autoStartup=false}) и включается при получении лидерства.
 *
 * <p>Группа консьюмера команд общая у пары, поэтому новый активный продолжает с позиции,
 * зафиксированной прежним: команды, пришедшие за время переключения, не теряются. Слишком
 * старые (шлюзы стояли оба) отсекает {@link CommandConsumer} по возрасту.
 *
 * <p>Остановка — асинхронная: вызов приходит из потока выборов (в том числе из колбэка
 * ребаланса), и ждать в нём остановки контейнера нельзя — затянули бы передачу лидерства.
 */
@Component
public class CommandListenerSwitch {

    private static final Logger log = LoggerFactory.getLogger(CommandListenerSwitch.class);

    private final KafkaListenerEndpointRegistry registry;

    public CommandListenerSwitch(KafkaListenerEndpointRegistry registry) {
        this.registry = registry;
    }

    @EventListener
    public void onLeadershipChanged(LeadershipChanged e) {
        MessageListenerContainer container = registry.getListenerContainer(CommandConsumer.LISTENER_ID);
        if (container == null) {
            log.warn("Консьюмер команд {} не найден — команды не принимаются", CommandConsumer.LISTENER_ID);
            return;
        }
        if (e.active()) {
            if (!container.isRunning()) {
                container.start();
                log.info("▶ Приём команд включён (экземпляр {})", e.instanceId());
            }
        } else if (container.isRunning()) {
            container.stop(() -> log.info("⏸ Приём команд выключен (экземпляр {} в резерве)", e.instanceId()));
        }
    }
}
