plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    application
}

group = "family.sync"
version = "0.1.0"

repositories { mavenCentral() }

val ktorVersion = "3.6.0"

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-auth-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("com.zaxxer:HikariCP:6.3.0")
    implementation("org.flywaydb:flyway-core:11.8.2")
    implementation("org.flywaydb:flyway-database-postgresql:11.8.2")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.2.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testImplementation("io.zonky.test:embedded-postgres:2.1.0")
}

kotlin { jvmToolchain(17) }

application {
    mainClass.set("family.sync.ApplicationKt")
}

tasks.test { useJUnit() }

/**
 * Regenerates `.kilo/sync-contract.json` from `SyncTables.ALL`. The Node rewrite in
 * `buckwheat-sync` consumes that file, so the payload key lists are never hand-copied.
 */
tasks.register<JavaExec>("generateSyncContract") {
    group = "verification"
    description = "Writes the Android/server sync contract as JSON"
    mainClass.set("family.sync.SyncContractExportKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = projectDir
}
