package com.scada.gateway.ha;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Выборы активного экземпляра пары на настоящем брокере (Testcontainers): ровно один активный,
 * передача лидерства при штатной остановке и при падении, и резерв, перезапущенный рядом с
 * живым активным, лидерство не отбирает.
 *
 * <p>Брокер — с group.min.session.timeout.ms=1000, экземпляры — session 2000 / heartbeat 500,
 * как в docker-compose.ha.yml. Время переключения печатается — это и есть «почти мгновенно».
 */
@Testcontainers(disabledWithoutDocker = true)
class LeaderElectorIT {

    private static final int SESSION_MS = 2000;
    private static final int HEARTBEAT_MS = 500;

    @Container
    static ConfluentKafkaContainer kafka =
            new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))
                    .withEnv("KAFKA_GROUP_MIN_SESSION_TIMEOUT_MS", "1000")
                    .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0");

    /** Своя группа на тест — тесты не видят экземпляры друг друга. */
    private final String group = "it-ha-" + UUID.randomUUID();
    private final List<LeaderElector> started = new ArrayList<>();

    /** Экземпляр пары и история его ролей (события LeadershipChanged). */
    private record Node(LeaderElector elector, List<LeadershipChanged> history) {}

    private Node node(String id, boolean simulateCrashOnStop) {
        List<LeadershipChanged> history = new CopyOnWriteArrayList<>();
        LeaderElector e = new LeaderElector(true, id, "it-ha-election", group, "scada-commands",
                kafka.getBootstrapServers(), SESSION_MS, HEARTBEAT_MS,
                event -> history.add((LeadershipChanged) event), new SimpleMeterRegistry()) {
            @Override
            Map<String, Object> consumerProps() {
                Map<String, Object> p = super.consumerProps();
                // Падение процесса (kill -9): LeaveGroup не уходит, брокер ждёт session-timeout.
                if (simulateCrashOnStop) p.put("internal.leave.group.on.close", false);
                return p;
            }
        };
        e.start();
        started.add(e);
        return new Node(e, history);
    }

    @AfterEach
    void stopAll() {
        started.forEach(LeaderElector::stop);
    }

    @Test
    void exactlyOneInstanceIsActive() {
        Node a = node("a", false);
        Node b = node("b", false);
        await(Duration.ofSeconds(20), () -> a.elector().isActive() || b.elector().isActive());
        // И так и остаётся: ни «двух активных», ни «ни одного».
        long until = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < until) {
            assertThat(a.elector().isActive() ^ b.elector().isActive()).as("ровно один активный").isTrue();
            sleep(50);
        }
    }

    @Test
    void gracefulStopHandsOverWithinASecondOrSo() {
        Node a = node("a", false);
        await(Duration.ofSeconds(20), () -> a.elector().isActive());
        Node b = node("b", false);
        sleep(2000); // b вошёл в группу резервом
        assertThat(b.elector().isActive()).isFalse();

        long t0 = System.nanoTime();
        a.elector().stop();
        await(Duration.ofSeconds(SESSION_MS / 1000 + 5), () -> b.elector().isActive());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("HA: штатная остановка активного → резерв активен через " + ms + " мс");
        assertThat(ms).as("без ожидания session-timeout").isLessThan(SESSION_MS);
    }

    @Test
    void crashedActiveIsReplacedWithinSessionTimeout() {
        Node a = node("a", true);
        await(Duration.ofSeconds(20), () -> a.elector().isActive());
        Node b = node("b", false);
        sleep(2000);
        assertThat(b.elector().isActive()).isFalse();

        long t0 = System.nanoTime();
        a.elector().stop(); // без LeaveGroup — как kill -9
        await(Duration.ofSeconds(SESSION_MS / 1000 + 10), () -> b.elector().isActive());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("HA: падение активного → резерв активен через " + ms + " мс");
        assertThat(ms).as("session-timeout + ребаланс").isLessThan(SESSION_MS + 3000);
    }

    @Test
    void restartedStandbyDoesNotStealLeadership() {
        Node a = node("a", false);
        await(Duration.ofSeconds(20), () -> a.elector().isActive());
        Node b = node("b", false);
        sleep(2000);
        // Резерв перезапускается (выход из группы и вход новым членом) — дважды.
        b.elector().stop();
        Node b2 = node("b2", false);
        sleep(2000);
        b2.elector().stop();
        Node b3 = node("b3", false);
        sleep(2000);

        assertThat(a.elector().isActive()).isTrue();
        assertThat(b3.elector().isActive()).isFalse();
        assertThat(a.history()).as("у активного ни одного ухода в резерв")
                .noneMatch(ch -> !ch.active());
    }

    private static void await(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("условие не наступило за " + timeout).isLessThan(deadline);
            sleep(20);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
