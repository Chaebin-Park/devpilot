# Stage 1: Build
FROM gradle:8.12-jdk17 AS builder
WORKDIR /app
COPY . .
RUN ./gradlew shadowJar --no-daemon -q

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN apk add --no-cache ripgrep

COPY --from=builder /app/build/libs/devpilot.jar .

EXPOSE 8080

# 설정 볼륨: 최초 실행 시 Setup Wizard가 ~/.devpilot/config.json 생성
# docker run -p 8080:8080 -v devpilot-config:/root/.devpilot devpilot
VOLUME ["/root/.devpilot"]

ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "devpilot.jar"]
CMD ["--web"]
