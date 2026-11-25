plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val propVersionName = (project.findProperty("versionName") as String?)?.trim()?.takeIf { it.isNotEmpty() } ?: "1.0.0"
val propVersionCode = (project.findProperty("versionCode") as String?)?.trim()?.toIntOrNull() ?: 10000

val defaultKeystore = file("../../obsidian-quicknotes/android/keystore/sideload-release.jks")
val hasReleaseKeystore = defaultKeystore.exists()

android {
    namespace = "com.nguyenhieu.edgedock"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nguyenhieu.edgedock"
        minSdk = 26
        targetSdk = 34
        versionCode = propVersionCode
        versionName = propVersionName
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = defaultKeystore
                storePassword = "quicknotes2026"
                keyAlias = "quicknotes"
                keyPassword = "quicknotes2026"
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}
