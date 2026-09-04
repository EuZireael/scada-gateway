package com.scada.gateway;

import com.scada.gateway.repository.ControllerRepository;
import com.scada.gateway.repository.TagRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционный тест полного контекста шлюза против РЕАЛЬНЫХ Postgres + Kafka
 * (Testcontainers). Заменяет удалённую заглушку ScadaGatewayApplicationTests и
 * проверяет то, что не ловят юнит-тесты:
 *  <ul>
 *    <li>миграции Flyway (V1…) применяются на ЧИСТОЙ БД;</li>
 *    <li>Hibernate {@code ddl-auto=validate} сходится со схемой Flyway
 *        (ловит дрейф сущностей и схемы);</li>
 *    <li>весь граф бинов поднимается с реальным брокером Kafka (шлюз при старте
 *        публикует событие в scada-events — продюсер реально пишет в брокер).</li>
 *  </ul>
 *
 * Контроллеры не подключаются: тестовый {@code controllers.yaml} пуст (см.
 * src/test/resources). {@code disabledWithoutDocker=true} — тест мягко
 * пропускается там, где Docker недоступен (напр. локально без демона).
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ScadaGatewayApplicationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static ConfluentKafkaContainer kafka =
            new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    /**
     * Kafka-bootstrap прокидываем СВОЙСТВОМ, а не через @ServiceConnection: в шлюзе есть
     * кастомная commandConsumerFactory (KafkaConfig), читающая ${spring.kafka.bootstrap-servers}
     * через @Value — ConnectionDetails от @ServiceConnection до неё не доходит. Свойство же
     * видят и авто-конфиг продюсера, и кастомная фабрика → единый брокер для обеих сторон.
     */
    @DynamicPropertySource
    static void kafkaProps(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private ControllerRepository controllerRepository;

    @Autowired
    private TagRepository tagRepository;

    @Test
    void contextLoads() {
        // Сам факт успешного подъёма контекста = Flyway применился, JPA validate
        // сошлась со схемой, Kafka/JPA бины сконфигурированы против реальной инфры.
    }

    @Test
    void schemaIsQueryableAndEmptyForTestConfig() {
        // Пустой тестовый controllers.yaml → ConfigurationService создаёт 0 записей.
        // Запрос заодно подтверждает: схема Flyway реально доступна через JPA, а боевой
        // controllers.yaml НЕ подхватился (тест изолирован).
        assertThat(controllerRepository.count()).isZero();
        assertThat(tagRepository.count()).isZero();
    }
}
