// Top-level build file. See app/build.gradle.kts for the app module.
buildscript {
    // Kotlin support is built into AGP, which otherwise pulls in an older
    // Kotlin Gradle plugin (2.2.x). Pin it to the version the app's
    // kotlin-stdlib dependency is built with so the compiler can read its
    // metadata.
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin") {
            version { strictly("2.4.20") }
        }
    }
}

plugins {
    // Kotlin support is built into AGP 9+, so the org.jetbrains.kotlin.android
    // plugin is no longer applied.
    id("com.android.application") version "9.4.1" apply false
}
