FROM maven:3.9.12-eclipse-temurin-25 AS build
ARG MAVEN_OPTS
WORKDIR /build
COPY pom.xml .
COPY config config
COPY src src
RUN --mount=type=cache,id=grallum-maven,target=/root/.m2 mvn -B -ntp -DskipTests package
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/target/application-0.1.0-SNAPSHOT.jar application.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java","-jar","application.jar"]
