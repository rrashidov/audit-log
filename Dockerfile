FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn clean package -DskipTests -q

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/audit-log-0.0.1-SNAPSHOT.jar app.jar
ENV TZ=UTC
ENV JAVA_OPTS="-Duser.timezone=UTC"
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "app.jar"]
