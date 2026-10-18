ARG MAVEN_IMAGE=maven:3.9.9-eclipse-temurin-17
FROM ${MAVEN_IMAGE} AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
COPY docker ./docker
RUN --mount=type=cache,target=/root/.m2 mvn --batch-mode -Dmaven.test.skip=true package \
    && javac -d /health docker/HealthProbe.java

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/*.jar /app/app.jar
COPY --from=build /health /app/health
USER 10001:10001
ENV SERVER_ADDRESS=0.0.0.0 SERVER_PORT=8081 JAVA_TOOL_OPTIONS=-Duser.timezone=Asia/Shanghai
EXPOSE 8081
HEALTHCHECK --interval=30s --timeout=8s --start-period=60s --retries=3 \
    CMD ["java", "-cp", "/app/health", "HealthProbe"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]
