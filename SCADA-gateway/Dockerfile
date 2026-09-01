# ============================================================================
# Образ шлюза SCADA (Spring Boot 3.5.16 / Java 21) из ГОТОВОГО jar.
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

# JVM-страховка (утечек в коде нет, это наблюдаемость + предсказуемость в контейнере):
#   MaxRAMPercentage=75 — брать 75% от лимита ПАМЯТИ КОНТЕЙНЕРА, а не 25% от RAM хоста
#     (по умолчанию голый java берёт ~25%; в K8s/compose задавай лимит памяти рядом);
#   -Xlog:gc* — писать GC-лог в stdout, чтобы при жалобах на «тормозит» были ДАННЫЕ,
#     а не гадание (частота minor/major GC, паузы, заполнение old gen).
# Переопределяется без пересборки: docker run -e JAVA_OPTS="...".
# sh -c + exec — чтобы java стала PID 1 и получала SIGTERM (чистый shutdown, @PreDestroy).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Xlog:gc*:stdout:time,uptime,level,tags"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
