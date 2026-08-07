import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The signing key is deliberately not in the repository. Copy
// keystore.properties.example to keystore.properties and point it at your own
// keystore to produce builds that install over an existing copy of the app.
// Without it the build still works and simply falls back to the debug key.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
// Read before it is used: Project.file() rejects an empty path outright, so a
// keystore.properties without a storeFile line would fail the build at
// configuration time instead of falling back to the debug key as promised.
val keystorePath = keystoreProps.getProperty("storeFile").orEmpty().trim()
val hasSigningKey = keystorePath.isNotEmpty() && rootProject.file(keystorePath).exists()

android {
    namespace = "com.dfwriter.slate"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dfwriter.slate"
        minSdk = 26
        // Target 29 on purpose: Chauvet is Android 11, and targeting 29 keeps
        // legacy external storage so the app can read/write /sdcard/Note
        // directly without depending on the system document picker.
        targetSdk = 29
        versionCode = 15
        versionName = "1.7.0"
    }

    signingConfigs {
        if (hasSigningKey) {
            create("sideload") {
                storeFile = rootProject.file(keystorePath)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        val signing = signingConfigs.findByName("sideload")
            ?: signingConfigs.getByName("debug")
        release {
            isMinifyEnabled = false
            signingConfig = signing
        }
        debug {
            signingConfig = signing
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
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        abortOnError = false
        // This app is sideloaded onto a single Android 11 device, never
        // published, so the Play Store's target-API floor does not apply.
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    // Deliberately zero third-party dependencies in the shipped APK: no
    // AndroidX, no Compose. Framework views start faster and repaint more
    // predictably on E Ink. The entries below are test-only.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
}
