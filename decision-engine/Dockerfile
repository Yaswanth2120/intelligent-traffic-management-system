FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
COPY decision-engine/pom.xml decision-engine/pom.xml
COPY decision-engine/src decision-engine/src
RUN mvn -q -pl decision-engine -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/decision-engine/target/decision-engine-*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
