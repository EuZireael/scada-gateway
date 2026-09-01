package com.scada.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Точка входа Spring Boot. Поднимает контекст SCADA-шлюза: опрос контроллеров
 * (OPC UA Phoenix + Modbus WAGO) -> нормализация значений -> Kafka (телеметрия/
 * события/алармы) и локальная БД; приём команд управления из монитора.
 */
@SpringBootApplication
@EnableScheduling
public class ScadaGatewayApplication {

    /** Запуск приложения: поднимает Spring-контекст, дальше всё делают бины (@Scheduled-опрос и т.д.). */
    public static void main(String[] args) {
        SpringApplication.run(ScadaGatewayApplication.class, args);
    }

}