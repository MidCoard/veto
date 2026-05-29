# Build stage
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY . .
RUN ./gradlew bootJar --no-daemon

# Extraction stage
FROM eclipse-temurin:21-jre-alpine AS extract
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# Final stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=extract /app/dependencies/ ./
COPY --from=extract /app/spring-boot-loader/ ./
COPY --from=extract /app/snapshot-dependencies/ ./
COPY --from=extract /app/application/ ./

EXPOSE 8443
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
