package org.example.scadagateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Дефолтный тест загрузки Spring-контекста. ВНИМАНИЕ: лежит в пакете org.example
 * (а не com.scada.gateway) и как @SpringBootTest поднимает ВЕСЬ контекст — то есть
 * требует доступных Postgres и Kafka, иначе падает. Настоящие модульные тесты — в
 * com.scada.gateway.* (без Spring, на Mockito). Кандидат на перенос в правильный
 * пакет или замену на интеграционный тест с Testcontainers.
 */
@SpringBootTest
class ScadaGatewayApplicationTests {

    @Test
    void contextLoads() {
    }

}
