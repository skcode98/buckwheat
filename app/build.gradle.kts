import java.util.Properties

// Release signing credentials. Kept out of the repo (see .gitignore): if keystore.properties
// is missing, release builds fall back to the debug key so local/test builds still work.
val keystoreProperties =
    Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) {
            load(file.inputStream())
        }
    }

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("dagger.hilt.android.plugin")
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.compose)
}

android {
    compileSdk = 36

    defaultConfig {
        applicationId = "com.danilkinkin.buckwheat"
        minSdk = 29
        targetSdk = 36
        versionCode = 32
        versionName = "10.0.0"
        testInstrumentationRunner = "com.danilkinkin.buckwheat.CustomTestRunner"
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("dagger.hilt.disableModulesHaveInstallInCheck", "true")
        arg("room.incremental", "true")
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }

        getByName("release") {
            isMinifyEnabled = true
            signingConfig =
                if (keystoreProperties.isNotEmpty()) {
                    signingConfigs.create("release") {
                        storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                        storePassword = keystoreProperties["storePassword"] as String
                        keyAlias = keystoreProperties["keyAlias"] as String
                        keyPassword = keystoreProperties["keyPassword"] as String
                    }
                } else {
                    signingConfigs.getByName("debug")
                }

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true

        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // android.util.Log and the other framework stubs throw "not mocked" by default, so any
            // failure path that logs is untestable and every test that reaches one dies for a reason
            // that has nothing to do with what it is testing. Returning default values instead makes
            // the stubs inert; about twenty classes in main log on paths that only run when something
            // has already gone wrong.
            isReturnDefaultValues = true
            all {
                it.jvmArgs("-ea")
                // 86 test classes, most on Robolectric: one fork runs them sequentially.
                // 4 forks x 1 GB fits the 8 GB box next to the 3 GB daemon; forkEvery
                // recycles the fork before Robolectric sandbox leaks bloat it.
                it.maxParallelForks = 4
                it.forkEvery = 150
                it.maxHeapSize = "1g"
            }
        }
    }

    sourceSets {
        getByName("debug") {
            assets.srcDir("$projectDir/schemas")
        }
    }

    bundle {
        language {
            enableSplit = false
        }
    }

    packaging {
        // Multiple dependency bring these files in. Exclude them to enable
        // our test APK to build (has no effect on our AARs)
        resources.excludes += "/META-INF/AL2.0"
        resources.excludes += "/META-INF/LGPL2.1"
    }
    namespace = "com.danilkinkin.buckwheat"
}

tasks.matching { it.name == "mergeDebugAssets" }.configureEach {
    dependsOn("kspDebugKotlin")
}

dependencies {
    // Core
    implementation(libs.kotlin.stdlib)
    implementation(libs.coroutines.android)

    // AndroidX
    implementation(libs.appcompat)
    implementation(libs.fragment)
    implementation(libs.activity.compose)

    // Compose
    implementation(libs.bundles.compose)
    implementation(libs.compose.material3.window.size)
    implementation(libs.compose.ui.tooling.preview)

    // DataStore
    implementation(libs.datastore.preferences)

    // RecyclerView
    implementation(libs.recyclerview)

    // Room
    implementation(libs.bundles.room)
    ksp(libs.room.compiler)

    // Hilt
    implementation(libs.bundles.hilt)

    // Lifecycle
    implementation(libs.bundles.lifecycle)

    // Glance
    implementation(libs.bundles.glance)

    // Core
    implementation(libs.core.splashscreen)
    implementation(libs.biometric)

    // Accompanist
    implementation(libs.accompanist.systemuicontroller)

    // Dagger/Hilt
    implementation(libs.dagger)
    implementation(libs.hilt.android)
    ksp(libs.bundles.hilt.ksp)
    ksp(libs.bundles.dagger.ksp)

    // Utils
    implementation(libs.commons.csv)
    implementation(libs.coil.compose)

    // Desugar
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Work
    implementation(libs.work.runtime.ktx)

    // Debug
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // Testing
    implementation("androidx.hilt:hilt-work:1.2.0")
    testImplementation(libs.bundles.testing)
    androidTestImplementation(libs.bundles.android.test)
    kspAndroidTest(libs.hilt.android.compiler)
}
