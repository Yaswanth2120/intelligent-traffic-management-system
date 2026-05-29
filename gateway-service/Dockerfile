FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
COPY gateway-service/pom.xml gateway-service/pom.xml
COPY feature-service/pom.xml feature-service/pom.xml
COPY decision-engine/pom.xml decision-engine/pom.xml
COPY gateway-service/src gateway-service/src
RUN mvn -q -pl gateway-service -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/gateway-service/target/gateway-service-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
