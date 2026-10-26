# One image recipe for both services: docker build --build-arg MODULE=ledgerflow-api .
# Expects the module's jar to be built already (./mvnw package), as CI does.

FROM eclipse-temurin:21-jre AS extract
ARG MODULE
WORKDIR /build
COPY ${MODULE}/target/${MODULE}-*.jar app.jar
# Split the fat jar into layers (dependencies change rarely, our code often) for better caching.
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY --from=extract /build/extracted/dependencies/ ./
COPY --from=extract /build/extracted/spring-boot-loader/ ./
COPY --from=extract /build/extracted/snapshot-dependencies/ ./
COPY --from=extract /build/extracted/application/ ./
USER app
# Size the heap from the container's memory limit, not the host's.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
