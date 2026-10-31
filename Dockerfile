# Image for one of the Spring services:
#   docker build --build-arg SERVICE=order-service -t ghcr.io/erjonfida/furniflow/order-service .
FROM eclipse-temurin:25-jdk AS build
ARG SERVICE
WORKDIR /src
COPY mvnw .
COPY .mvn .mvn
COPY ${SERVICE} ${SERVICE}
# The cache mount keeps downloaded dependencies between builds without putting them in the image. Tests run in CI.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -f ${SERVICE} package -DskipTests \
 && java -Djarmode=tools -jar ${SERVICE}/target/${SERVICE}-*.jar extract --layers --launcher --destination /app

FROM eclipse-temurin:25-jre
WORKDIR /app
# One layer per part of the jar. Libraries rarely change, so a code change only rebuilds and pushes the last layer.
COPY --from=build /app/dependencies/ ./
COPY --from=build /app/spring-boot-loader/ ./
COPY --from=build /app/snapshot-dependencies/ ./
COPY --from=build /app/application/ ./
USER 1000
# The heap may use 75% of the container's memory limit instead of the default 25%
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
