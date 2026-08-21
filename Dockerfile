# Build stage
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /app
COPY . .
RUN ./gradlew :veto-core:bootJar --no-daemon

# Runtime stage
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=build /app/veto-core/build/libs/veto-core-*.jar app.jar
COPY LICENSE /licenses/LICENSE

LABEL org.opencontainers.image.source="https://github.com/MidCoard/veto"
LABEL org.opencontainers.image.licenses="AGPL-3.0-only"

EXPOSE 8443
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]
