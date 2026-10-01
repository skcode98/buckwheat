# Two stages: build the standalone Ktor server, then ship only the JVM runtime.
#
# Why Docker at all: Render's native runtimes always build at the repository
# root, and the root build.gradle.kts applies the Android and KSP plugins. A
# native Render build would try to compile the Android app and fail without an
# Android SDK. This file copies server/ and nothing else, so the Android app,
# Gradle, the Android SDK and Node are all irrelevant to the image.
#
# server/ is a self-contained Gradle build: its own settings.gradle.kts declares
# only `rootProject.name = "buckwheat-sync-server"` and never includes the
# repository-root settings, which include just :app. So the build below runs
# inside an ordinary Kotlin/JVM project.

# Build stage.
# Tag choice, as of 2026-10-01:
#   * JDK major 17 matches `kotlin { jvmToolchain(17) }` in
#     server/build.gradle.kts, so no toolchain download or provisioning happens.
#     Flyway 11 also requires Java 17 or newer. Ktor 3.6 and Kotlin 2.2 run fine
#     on 17.
#   * The 8.14.x line is the last Gradle 8 line and comfortably covers Kotlin
#     2.2.0, which needs Gradle 7.6.3 or newer.
#   * Only the build stage runs as root; the runtime stage below drops to an
#     unprivileged user.
# The previous pin was gradle:8.14.3-jdk17. That tag still resolves, but it has
# been dropped from the Docker Official Images tag list for library/gradle, so
# it can disappear upstream at any time. 8.14.5-jdk17-noble is on that list.
FROM gradle:8.14.5-jdk17-noble AS build
WORKDIR /workspace/server
# Copy the build scripts before the sources. This does not buy a cached
# dependency layer, because a single RUN below still recompiles whenever any
# source file changes; it only keeps the two COPY layers separate so a source
# edit does not invalidate the build-script layer.
COPY server/build.gradle.kts server/settings.gradle.kts ./
COPY server/src ./src
# `installDist` is the `application` plugin's task, so it depends on the jar and
# not on `test`; -x test is belt and braces. No Node and no Android SDK are
# needed: settings.gradle.kts in server/ pulls in nothing but mavenCentral.
RUN gradle --no-daemon --console=plain installDist -x test

# Runtime stage. A JRE is enough: the application ships as a start script plus
# jars, and `installDist` produces bin/buckwheat-sync-server because
# server/settings.gradle.kts sets rootProject.name to exactly that.
FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app
COPY --from=build /workspace/server/build/install/buckwheat-sync-server /app
# Unprivileged, so a remote-code-execution bug in the server or in a dependency is
# not immediately root in the container. The start script only needs to read /app.
RUN groupadd --system buckwheat \
 && useradd --system --gid buckwheat --home-dir /app --no-create-home buckwheat \
 && chown -R buckwheat:buckwheat /app
USER buckwheat
# 8080 is only the local default from Config.port. Render injects its own PORT
# and loadConfig reads it, so nothing here needs to be pinned to 8080 in
# production. EXPOSE is documentation; it does not route traffic.
EXPOSE 8080
CMD ["/app/bin/buckwheat-sync-server"]