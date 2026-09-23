plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.maxsc2.miniuna"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.maxsc2.miniuna"
        minSdk = 26
        targetSdk = 35
        // CI passes VERSION_CODE=${{ github.run_number }} so every build installs as an update.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 2
        versionName = "0.2.0"
    }

    signingConfigs {
        create("miniuna") {
            // CI provides KEYSTORE_PATH + passwords via secrets (persistent key).
            // Local builds fall back to the debug keystore.
            val ksPath = System.getenv("KEYSTORE_PATH")
                ?: "${System.getProperty("user.home")}/.android/debug.keystore"
            storeFile = file(ksPath)
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "android"
            keyAlias = System.getenv("KEY_ALIAS") ?: "androiddebugkey"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "android"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("miniuna")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    testImplementation("junit:junit:4.13.2")
}
