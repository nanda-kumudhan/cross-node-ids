FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B dependency:go-offline
COPY src src
RUN ./mvnw -B verify

FROM eclipse-temurin:21.0.9_10-jre-jammy
RUN groupadd --gid 10001 ids && useradd --uid 10001 --gid ids --no-create-home ids
WORKDIR /app
COPY --from=build /src/target/ids-engine-0.1.0.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]
