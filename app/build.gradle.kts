plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.smouha.call"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.smouha.call"
        minSdk = 21
        targetSdk = 34
        versionCode = 11
        versionName = "1.5.3"
    }

    signingConfigs {
        create("release") {
            storeFile = file("/tmp/smouha-keys/smouha-release.jks")
            storePassword = System.getenv("SMOUHA_KEYSTORE_PASS") ?: "SmouhaOps2026#Key"
            keyAlias = System.getenv("SMOUHA_KEY_ALIAS") ?: "smouha_release_key"
            keyPassword = System.getenv("SMOUHA_KEY_PASS") ?: "SmouhaOps2026#Key"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
