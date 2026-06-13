FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

FROM eclipse-temurin:25-jre-alpine AS runtime
RUN apk add --no-cache curl
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8000
HEALTHCHECK --interval=15s --timeout=5s --retries=10 \
  CMD curl -sf http://localhost:8000/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
