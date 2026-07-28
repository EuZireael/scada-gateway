package com.scada.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ScadaGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScadaGatewayApplication.class, args);
    }

}