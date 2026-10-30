FROM maven:3.9.16-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S utetaste && adduser -S utetaste -G utetaste
WORKDIR /app
COPY --from=build --chown=utetaste:utetaste /app/target/utetaste-1.0.0.jar app.jar
USER utetaste
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
