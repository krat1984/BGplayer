import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI run number makes every build installable over the previous one.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.bgplayer.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bgplayer.app"
        minSdk = 24
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // Fixed signing key: every build has the same signature, so a new APK installs over
    // the old one without uninstalling (settings are kept).
    // The key is NOT in the repository: CI writes it from the BG_KEYSTORE_BASE64 secret
    // to app/bgplayer.keystore (git-ignored). Locally the same file can be placed by hand.
    // Without it the standard per-machine debug key is used.
    val bgKeystore = file("bgplayer.keystore")
    signingConfigs {
        create("bg") {
            storeFile = bgKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            if (bgKeystore.exists()) signingConfig = signingConfigs.getByName("bg")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (bgKeystore.exists()) signingConfigs.getByName("bg") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.all {
            it.testLogging {
                events("passed", "failed", "skipped")
                exceptionFormat = TestExceptionFormat.FULL
                showStandardStreams = false
            }
        }
    }
}

dependencies {
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.documentfile:documentfile:1.0.1")

    testImplementation("junit:junit:4.13.2")
}
