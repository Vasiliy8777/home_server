FROM gradle:8.7-jdk21 AS build

WORKDIR /app

COPY build.gradle settings.gradle ./
COPY gradle ./gradle
COPY src ./src

RUN gradle bootJar --no-daemon

FROM eclipse-temurin:21-jre

WORKDIR /app

RUN apt-get update && apt-get install -y ffmpeg && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/build/libs/*.jar app.jar

RUN mkdir -p /data

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]