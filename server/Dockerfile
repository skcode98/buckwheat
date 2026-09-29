FROM gradle:8.14.3-jdk17 AS build
WORKDIR /workspace/server
COPY server/build.gradle.kts server/settings.gradle.kts ./
COPY server/src ./src
RUN gradle --no-daemon --console=plain installDist -x test

FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app
COPY --from=build /workspace/server/build/install/buckwheat-sync-server /app
EXPOSE 8080
CMD ["/app/bin/buckwheat-sync-server"]
