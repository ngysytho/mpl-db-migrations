FROM eclipse-temurin:21.0.9_10-jdk-jammy AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline
COPY src src
COPY tests tests
RUN ./mvnw -B -ntp package
FROM eclipse-temurin:21.0.9_10-jre-jammy
LABEL org.opencontainers.image.version="1.0.0"
WORKDIR /migration
COPY --from=build /build/target/mpl-db-migrations-1.0.0-runner.jar /migration/migrations.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/migration/migrations.jar"]
CMD ["validate"]
