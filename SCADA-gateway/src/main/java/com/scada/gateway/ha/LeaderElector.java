package com.scada.gateway.ha;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.CooperativeStickyAssignor;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Горячее резервирование шлюза: выборы активного экземпляра через группу потребителей Kafka.
 *
 * <p><b>Как устроено.</b> Оба экземпляра пары подписаны одной группой
 * ({@code gateway.ha.group-id}) на служебный топик из одной партиции
 * ({@code gateway.ha.topic}). Kafka отдаёт партицию ровно одному члену группы — он и
 * активный. Упал активный — брокер перестаёт получать от него heartbeat и через
 * {@code session-timeout-ms} отдаёт партицию резервному. Остановили штатно — экземпляр
 * покидает группу сразу, и резерв подхватывает за доли секунды.
 *
 * <p><b>Почему Kafka, а не отдельный арбитр.</b> Kafka — единственный канал шлюза к монитору
 * (телеметрия, события, команды), поэтому выборы через неё не добавляют новой точки отказа.
 * И split-brain здесь безвреден: экземпляр, потерявший связь с брокером и не узнавший, что
 * лидерство ушло, всё равно ничего не может опубликовать или получить.
 *
 * <p><b>Защиты.</b>
 * <ul>
 *   <li>CooperativeStickyAssignor: подключившийся (перезапущенный) резерв не отбирает
 *       лидерство у живого активного — лишних переключений нет.</li>
 *   <li>Самоотключение: если heartbeat к брокеру не уходил дольше session-timeout, брокер уже
 *       мог отдать партицию другому — активный сам уходит в резерв.</li>
 *   <li>Колбэки ребаланса быстрые; слушатели смены роли не должны блокировать этот поток
 *       (иначе поток выборов не опрашивал бы Kafka и выпал бы из группы).</li>
 * </ul>
 *
 * <p>При {@code gateway.ha.enabled=false} (по умолчанию) экземпляр всегда активный — поведение
 * одиночного шлюза, Kafka для выборов не используется.
 */
@Component
public class LeaderElector implements Leadership, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(LeaderElector.class);

    /** Пауза между опросами группы: за столько же замечаем потерю/получение лидерства. */
    private static final Duration POLL = Duration.ofMillis(100);
    /** Пауза перед повтором после ошибки выборов (брокер недоступен, неверная настройка). */
    private static final long RETRY_BACKOFF_MS = 2000;

    private final boolean enabled;
    private final String instanceId;
    private final String topic;
    private final String groupId;
    private final String bootstrapServers;
    private final int sessionTimeoutMs;
    private final int heartbeatIntervalMs;
    private final ApplicationEventPublisher events;
    private final TopicPartition leaderPartition;

    private volatile boolean active;
    private volatile Instant roleSince = Instant.now();
    private volatile boolean running;
    private volatile KafkaConsumer<byte[], byte[]> consumer;
    private Thread thread;

    public LeaderElector(@Value("${gateway.ha.enabled:false}") boolean enabled,
                         @Value("${gateway.ha.instance-id:}") String instanceId,
                         @Value("${gateway.ha.topic:scada-gateway-ha}") String topic,
                         @Value("${gateway.ha.group-id:}") String groupId,
                         @Value("${kafka.topics.commands:scada-commands}") String commandsTopic,
                         @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
                         @Value("${gateway.ha.session-timeout-ms:6000}") int sessionTimeoutMs,
                         @Value("${gateway.ha.heartbeat-interval-ms:1000}") int heartbeatIntervalMs,
                         ApplicationEventPublisher events,
                         MeterRegistry meterRegistry) {
        this.enabled = enabled;
        this.instanceId = instanceId == null || instanceId.isBlank() ? defaultInstanceId() : instanceId;
        this.topic = topic;
        // Пара — это экземпляры, обслуживающие одни и те же топики. Группа по умолчанию выводится
        // из топика команд: шлюзы разных станций (свои топики) в одну пару не попадут.
        this.groupId = groupId == null || groupId.isBlank() ? "scada-gateway-ha." + commandsTopic : groupId;
        this.bootstrapServers = bootstrapServers;
        this.sessionTimeoutMs = sessionTimeoutMs;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.events = events;
        this.leaderPartition = new TopicPartition(topic, 0);
        // Одиночный экземпляр активен сразу — события старта уходят в Kafka, как раньше.
        this.active = !enabled;
        Gauge.builder("scada.ha.active", this, e -> e.isActive() ? 1 : 0)
                .description("1 — экземпляр активный, 0 — резервный")
                .tag("instance", this.instanceId)
                .register(meterRegistry);
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public boolean isHaEnabled() {
        return enabled;
    }

    @Override
    public String instanceId() {
        return instanceId;
    }

    /** С какого момента экземпляр в текущей роли. */
    public Instant roleSince() {
        return roleSince;
    }

    public String groupId() {
        return groupId;
    }

    // ------------------------------------------------------------------ жизненный цикл --

    @Override
    public void start() {
        running = true;
        if (!enabled) {
            // Событие — чтобы включатель консьюмера команд запустил его, как при получении
            // лидерства: одна логика для одиночного экземпляра и для пары.
            events.publishEvent(new LeadershipChanged(true, instanceId, "резервирование выключено"));
            return;
        }
        log.info("🛡 Горячий резерв: экземпляр {} (группа {}, топик {}, session {} мс, heartbeat {} мс)",
                instanceId, groupId, topic, sessionTimeoutMs, heartbeatIntervalMs);
        thread = new Thread(this::electionLoop, "ha-elector");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Штатная остановка: сначала перестаём публиковать, потом покидаем группу (close шлёт
     * LeaveGroup) — резерв получает лидерство без ожидания session-timeout.
     */
    @Override
    public void stop() {
        running = false;
        setActive(false, "остановка экземпляра");
        KafkaConsumer<byte[], byte[]> c = consumer;
        if (c != null) c.wakeup();
        if (thread != null) {
            try {
                thread.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Останавливаться раньше контейнеров Kafka-слушателей (у них фаза MAX_VALUE - 100). */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 50;
    }

    // ----------------------------------------------------------------------- выборы --

    private void electionLoop() {
        while (running) {
            try (KafkaConsumer<byte[], byte[]> c = new KafkaConsumer<>(consumerProps())) {
                consumer = c;
                ensureTopic();
                c.subscribe(List.of(topic), new RebalanceListener());
                while (running) {
                    c.poll(POLL);
                    boolean owner = c.assignment().contains(leaderPartition);
                    boolean stale = owner && heartbeatStale(c);
                    if (owner && !stale) {
                        setActive(true, "получено лидерство в группе " + groupId);
                    } else if (stale) {
                        setActive(false, "нет heartbeat к брокеру дольше " + sessionTimeoutMs
                                + " мс — лидерство могло перейти к резерву");
                    }
                }
            } catch (WakeupException e) {
                // stop(): штатный выход, close() в try-with-resources покинет группу.
            } catch (Exception e) {
                setActive(false, "ошибка выборов: " + e.getMessage());
                log.error("🛡 Выборы активного экземпляра прерваны: {}. Проверьте брокер и настройки: "
                                + "session-timeout-ms ({}) должен быть в пределах group.min/max.session.timeout.ms "
                                + "брокера. Повтор через {} мс", e.toString(), sessionTimeoutMs, RETRY_BACKOFF_MS);
                sleepQuietly(RETRY_BACKOFF_MS);
            } finally {
                consumer = null;
            }
        }
        setActive(false, "выборы остановлены");
    }

    /** Настройки консьюмера выборов (package-private: тест имитирует падение без LeaveGroup). */
    Map<String, Object> consumerProps() {
        Map<String, Object> p = new HashMap<>();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        p.put(ConsumerConfig.CLIENT_ID_CONFIG, "scada-gateway-ha-" + instanceId);
        p.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, sessionTimeoutMs);
        p.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, heartbeatIntervalMs);
        // Поток выборов опрашивает раз в 100 мс; если он завис на 30 с — пусть выпадет из
        // группы и отдаст лидерство (зависший экземпляр не должен оставаться активным).
        p.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 30_000);
        p.put(ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG, List.of(CooperativeStickyAssignor.class.getName()));
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        p.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        return p;
    }

    /** Служебный топик из одной партиции; уже существующий не трогаем. */
    private void ensureTopic() {
        Map<String, Object> props = Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        try (AdminClient admin = AdminClient.create(props)) {
            // Фактор репликации — по умолчанию брокера (на кластере из 3 брокеров это 3).
            admin.createTopics(List.of(new NewTopic(topic, Optional.of(1), Optional.empty())))
                    .all().get(10, TimeUnit.SECONDS);
            log.info("🛡 Создан служебный топик выборов {}", topic);
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof TopicExistsException)) {
                log.warn("🛡 Топик {} не создан: {}", topic, e.getCause() != null ? e.getCause().getMessage() : e);
            }
        } catch (Exception e) {
            log.warn("🛡 Топик {} не создан: {}", topic, e.getMessage());
        }
    }

    /**
     * Heartbeat к координатору группы не уходил дольше session-timeout: брокер уже мог
     * исключить нас и отдать партицию. Метрика клиента — в целых секундах; бесконечность —
     * heartbeat ещё не отправлялся (только что вошли в группу), это не «протухло».
     */
    private boolean heartbeatStale(KafkaConsumer<byte[], byte[]> c) {
        for (Map.Entry<MetricName, ? extends Metric> m : c.metrics().entrySet()) {
            MetricName name = m.getKey();
            if ("last-heartbeat-seconds-ago".equals(name.name())
                    && "consumer-coordinator-metrics".equals(name.group())
                    && m.getValue().metricValue() instanceof Double secondsAgo) {
                return !secondsAgo.isInfinite() && secondsAgo * 1000 > sessionTimeoutMs;
            }
        }
        return false;
    }

    /** Смена роли: одна точка — лог, метка времени, событие Spring. */
    synchronized void setActive(boolean value, String reason) {
        if (active == value) return;
        active = value;
        roleSince = Instant.now();
        if (value) {
            log.warn("🟢 Экземпляр {} АКТИВНЫЙ: {}", instanceId, reason);
        } else {
            log.warn("🟡 Экземпляр {} в РЕЗЕРВЕ: {}", instanceId, reason);
        }
        events.publishEvent(new LeadershipChanged(value, instanceId, reason));
    }

    /**
     * Потеря партиции — уходим в резерв ДО того, как её получит другой экземпляр (колбэк
     * вызывается внутри poll, до завершения ребаланса). Получение обрабатывает цикл выборов.
     */
    private final class RebalanceListener implements ConsumerRebalanceListener {
        @Override
        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            if (partitions.contains(leaderPartition)) setActive(false, "лидерство передано другому экземпляру");
        }

        @Override
        public void onPartitionsLost(Collection<TopicPartition> partitions) {
            if (partitions.contains(leaderPartition)) setActive(false, "брокер исключил экземпляр из группы");
        }

        @Override
        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            // Решение принимает цикл выборов по assignment() после poll.
        }
    }

    private static String defaultInstanceId() {
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) {
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                host = "gateway";
            }
        }
        return host + "-" + ProcessHandle.current().pid();
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
