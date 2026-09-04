FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew dependencies --no-daemon >/dev/null
COPY src src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/hof-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
# .env의 JAVA_TOOL_OPTIONS로 유휴 연결 만료 시간(초)을 변경할 수 있다.
ENV JAVA_TOOL_OPTIONS="-Djdk.httpclient.keepalive.timeout=4"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
