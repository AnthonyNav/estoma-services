FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw mvnw
COPY platform-bff/pom.xml platform-bff/pom.xml
COPY platform-bff/src platform-bff/src
RUN chmod +x ./mvnw && ./mvnw -B -f platform-bff/pom.xml package -DskipTests
FROM eclipse-temurin:25-jre
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app
COPY --from=build /workspace/platform-bff/target/*.jar /app/app.jar
RUN chown -R app:app /app
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
