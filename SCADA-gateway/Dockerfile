# ============================================================================
# Образ шлюза SCADA (Spring Boot 3.5.16 / Java 21) из ГОТОВОГО jar.
# Jar собирается на хосте (mvn -DskipTests package) — быстрее и надёжнее, чем
# тяжёлая maven-сборка внутри контейнера. Собрать: ./up.sh или mvn package.
# ============================================================================
FROM eclipse-temurin:25-jre
WORKDIR /app

# curl — для HEALTHCHECK (опрос /actuator/health из docker-compose).
# Непривилегированный пользователь scada — процесс шлюза НЕ должен бежать под root
# (принцип наименьших привилегий; при пробое меньше поверхность атаки).
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd -r scada && useradd -r -g scada -u 1001 scada

# Готовый fat-jar из target/. Имя фиксировано финальным <finalName> из pom
# (SCADA-gateway-0.0.1-SNAPSHOT.jar); берём любой *.jar, но НЕ *-sources/-javadoc.
# --chown: jar принадлежит scada (читается непривилегированным процессом).
COPY --chown=scada:scada target/SCADA-gateway-*.jar app.jar

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

# Дальше бежим под непривилегированным пользователем (curl/health ему доступны,
# запись в /app не нужна — jar только читается).
USER scada
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
