# Multi-stage Dockerfile for CampusTransit Java Backend
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app

# Copy Java source code and the MySQL driver
COPY *.java ./
COPY lib/mysql-connector-j-26.7.0/mysql-connector-j-26.7.0.jar lib/mysql-connector-j-26.7.0/

# Compile the backend and its dependencies, excluding standalone test programs
RUN javac -sourcepath . -cp ".:lib/mysql-connector-j-26.7.0/mysql-connector-j-26.7.0.jar" BackendServer.java

# Production Runtime Image
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Copy compiled classes and libraries
COPY --from=build /app/*.class /app/
COPY --from=build /app/lib/mysql-connector-j-26.7.0/mysql-connector-j-26.7.0.jar /app/lib/mysql-connector-j-26.7.0/
COPY --from=build /app/frontend/ /app/frontend/

# Render automatically sets PORT at runtime; default is 8080
ENV PORT=8080
EXPOSE 8080

# Launch Java Backend Server
CMD ["sh", "-c", "java -cp '.:lib/mysql-connector-j-26.7.0/mysql-connector-j-26.7.0.jar' BackendServer"]
