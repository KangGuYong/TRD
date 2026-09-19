# syntax=docker/dockerfile:1
# 백엔드 단일 실행 파일(:app:bootJar → trend-radar.jar) 이미지. 빌드 컨텍스트는 backend/.
# 테스트는 여기서 돌리지 않는다 — 통합 테스트는 Testcontainers(Docker)가 필요해 호스트에서 ./gradlew test.

# ── build ──
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY . .
# Windows 체크아웃(core.autocrlf=true)이면 gradlew가 CRLF라 리눅스 셸에서 실행되지 않는다.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
# Gradle 배포판·의존성은 캐시 마운트에 둬서 재빌드 때 다시 받지 않는다.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :app:bootJar

# ── runtime ──
FROM eclipse-temurin:17-jre
# JVM 타임존은 UTC로 고정한다. 저장은 UTC(ClockConfig도 systemUTC)이고, 배치 cron 기본값도 UTC 기준이다
# (verdict_runner 18:00 UTC = 03:00 KST). 서버 로컬 타임존에 따라 배치 시각이 달라지지 않게 한다.
ENV TZ=UTC
RUN useradd --system --uid 10001 --home-dir /app --shell /usr/sbin/nologin trd
WORKDIR /app
COPY --from=build /workspace/app/build/libs/trend-radar.jar /app/trend-radar.jar
USER trd
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/trend-radar.jar"]
