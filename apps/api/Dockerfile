FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN chmod +x ./mvnw && ./mvnw -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/trust-grid-api-*.jar /app/trust-grid-api.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/trust-grid-api.jar"]
