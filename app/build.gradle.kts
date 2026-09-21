import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.watermarkhu.mijn3park"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "dev.watermarkhu.mijn3park"
        minSdk = 23
        // Mirrors app/module.toml (targetSdk = 35).
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }

    // Acode layout keeps Kotlin sources under src/main/kotlin; include it
    // alongside the Gradle default src/main/java.
    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("androidx.security:security-crypto:1.1.0")
}
