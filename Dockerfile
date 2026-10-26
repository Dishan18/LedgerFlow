# One image recipe for both services:
#   docker build --build-arg MODULE=ledgerflow-api .
#   docker build --build-arg MODULE=mock-bank .

# 1. Build the module's jar (tests run in CI before this, so they're skipped here).
FROM eclipse-temurin:21-jdk AS build
ARG MODULE
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY common/pom.xml common/
COPY ledgerflow-api/pom.xml ledgerflow-api/
COPY mock-bank/pom.xml mock-bank/
# Dependencies in their own layer: rebuilt only when a pom changes, not on every code change.
RUN ./mvnw -B -q -pl ${MODULE} -am dependency:go-offline -DexcludeReactor=true || true
COPY common/src common/src
COPY ${MODULE}/src ${MODULE}/src
RUN ./mvnw -B -q -pl ${MODULE} -am package -DskipTests \
    && cp ${MODULE}/target/${MODULE}-*.jar /src/app.jar

# 2. Split the fat jar into layers (dependencies change rarely, our code often).
FROM eclipse-temurin:21-jre AS extract
WORKDIR /build
COPY --from=build /src/app.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# 3. Runtime: JRE only, non-root.
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
