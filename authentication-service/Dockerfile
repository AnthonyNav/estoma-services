FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw mvnw
COPY authentication-service/pom.xml authentication-service/pom.xml
COPY authentication-service/src authentication-service/src

RUN chmod +x ./mvnw && ./mvnw -B -f authentication-service/pom.xml package -DskipTests

FROM eclipse-temurin:25-jre

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"

WORKDIR /app

RUN groupadd --system app && useradd --system --gid app --home-dir /app --shell /usr/sbin/nologin app
COPY --from=build /workspace/authentication-service/target/*.jar /app/app.jar
RUN chown -R app:app /app

USER app

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
