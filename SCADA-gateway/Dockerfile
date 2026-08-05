# ============================================================================
# Образ шлюза SCADA (Spring Boot 3.2.4 / Java 21) из ГОТОВОГО jar.
# Jar собирается на хосте (mvn -DskipTests package) — быстрее и надёжнее, чем
# тяжёлая maven-сборка внутри контейнера. Собрать: ./up.sh или mvn package.
# ============================================================================
FROM eclipse-temurin:21-jre
WORKDIR /app

# Готовый fat-jar из target/. Имя фиксировано финальным <finalName> из pom
# (SCADA-gateway-0.0.1-SNAPSHOT.jar); берём любой *.jar, но НЕ *-sources/-javadoc.
COPY target/SCADA-gateway-*.jar app.jar

# HTTP/actuator шлюза
EXPOSE 8888

# Хосты Postgres/Kafka/симулятора приходят из env (см. docker-compose.yml):
#   SPRING_DATASOURCE_URL, SPRING_KAFKA_BOOTSTRAP_SERVERS, SIM_HOST
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
