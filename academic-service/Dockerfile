FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw mvnw
COPY academic-service/pom.xml academic-service/pom.xml
COPY academic-service/src academic-service/src
RUN chmod +x ./mvnw && ./mvnw -B -f academic-service/pom.xml package -DskipTests
FROM eclipse-temurin:25-jre
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app
COPY --from=build /workspace/academic-service/target/*.jar /app/app.jar
RUN chown -R app:app /app
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
