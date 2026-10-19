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
            all { it.jvmArgs("-ea") }
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
    testImplementation(libs.bundles.testing)
    androidTestImplementation(libs.bundles.android.test)
    kspAndroidTest(libs.hilt.android.compiler)
}
