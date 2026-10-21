FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY backend/pom.xml backend/pom.xml
COPY backend/app backend/app
COPY rules rules
RUN mvn -q -f backend/pom.xml -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/backend/app/target/pifa-app-*.jar app.jar
USER 1000
ENTRYPOINT ["java", "-jar", "app.jar"]
