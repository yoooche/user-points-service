FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# Dependencies get their own layer, so a source change does not download them again.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests \
 && java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination /layers

FROM eclipse-temurin:21-jre
# rocketmq-client writes its own log under the home directory.
RUN groupadd --system app && useradd --system --create-home --gid app app
WORKDIR /app
COPY --from=build /layers/dependencies/ ./
COPY --from=build /layers/spring-boot-loader/ ./
COPY --from=build /layers/snapshot-dependencies/ ./
COPY --from=build /layers/application/ ./
USER app
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# Exec form keeps java as PID 1, so it receives SIGTERM and shuts down gracefully.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]