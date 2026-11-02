# Multi-stage Dockerfile for CampusTransit Java Backend
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app

# Copy repository source code and MySQL driver
COPY . .

# Compile Java classes with MySQL Connector JAR
RUN javac -cp ".:lib/mysql-connector-j-26.7.0/mysql-connector-j-26.7.0.jar" *.java

# Production Runtime Image
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Copy compiled classes and libraries
COPY --from=build /app/*.class /app/
COPY --from=build /app/lib/ /app/lib/
COPY --from=build /app/frontend/ /app/frontend/

# Render automatically sets PORT at runtime; default is 8080
ENV PORT=8080
EXPOSE 8080

# Launch Java Backend Server
CMD ["sh", "-c", "java -cp '.:lib/mysql-connector-j-26.7.0/mysql-connector-j-26.7.0.jar' BackendServer"]
