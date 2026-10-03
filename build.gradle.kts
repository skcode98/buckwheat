subprojects {
    pluginManager.apply("com.diffplug.spotless")

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            // The default target is `src/{main,test}/kotlin`, which does not exist in this project --
            // its Kotlin lives in `src/{main,test,androidTest}/java`. With an empty target the tasks
            // are permanently UP-TO-DATE and succeed vacuously, which is worse than having no gate: it
            // reported success over a file the Kotlin compiler rejected, and a reviewer took that green
            // as evidence the code was formatted.
            target("src/main/java/**/*.kt", "src/test/java/**/*.kt", "src/androidTest/java/**/*.kt")
            ktfmt()    // has its own section below
            ktlint()   // has its own section below
            diktat()   // has its own section below
            prettier() // has its own section below
        }
        kotlinGradle {
            target("*.gradle.kts") // default target for kotlinGradle
            ktlint() // or ktfmt() or prettier()
        }
    }
}

plugins {
    id("com.android.application") version "8.11.1" apply false
    id("com.android.library") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
    id("com.google.dagger.hilt.android") version "2.57" apply false
    id("com.diffplug.spotless") version "7.1.0"
    id("com.google.android.libraries.mapsplatform.secrets-gradle-plugin") version "2.0.1" apply false
    id("com.github.ben-manes.versions") version "0.52.0"
    id("com.google.devtools.ksp") version "2.2.0-2.0.2" apply false
}
