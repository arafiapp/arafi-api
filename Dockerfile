# ====================================================================
# STAGE 1: SYSTEM COMPILATION ENGINE
# ====================================================================
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder
WORKDIR /build

# Pre-cache Maven dependency tracks to speed up subsequent GitHub pipeline actions
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Inject application code sources and trigger package compilation routines
COPY src ./src
RUN mvn clean package -DskipTests

# ====================================================================
# STAGE 2: LIGHTWEIGHT RUNTIME CONTAINER
# ====================================================================
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Harden security: Run the microservice under a non-root application system container user
RUN addgroup -S arafigroup && adduser -S arafiuser -G arafigroup
USER arafiuser

# Copy the compiled executable artifact fat JAR layer straight from stage 1
COPY --from=builder /build/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]