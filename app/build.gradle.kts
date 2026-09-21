plugins {
    id("com.android.application")
}

android {
    namespace = "com.watermarkhu.mijn3park"
    compileSdk = 37
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.watermarkhu.mijn3park"
        minSdk = 23
        targetSdk = 36
        // Placeholders for local/debug builds. release.yml patches these with
        // the real versionName (semantic version) and versionCode (GitHub
        // release count) before building the published bundle.
        versionCode = 1
        versionName = "0.0.0-dev"
    }

    // Release signing. Values come from the environment (CI release job /
    // local shell), never committed. See .github/workflows/release.yml.
    signingConfigs {
        create("release") {
            System.getenv("MIJN3PARK_KEYSTORE_FILE")?.let { storeFile = file(it) }
            storePassword = System.getenv("MIJN3PARK_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("MIJN3PARK_KEY_ALIAS")
            keyPassword = System.getenv("MIJN3PARK_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            // Sign only when the keystore is provided, so unsigned release
            // builds (and all debug builds) still work without the secrets.
            signingConfig = System.getenv("MIJN3PARK_KEYSTORE_FILE")
                ?.let { signingConfigs.getByName("release") }
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // With built-in Kotlin (AGP 9+), the Kotlin jvmTarget defaults to
    // targetCompatibility, and src/main/kotlin is a default source directory.
    // 11 is required by current AndroidX artifacts (e.g. fragment-ktx 1.9.0).
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        // Fail the build on lint errors and on any warning not already
        // recorded in the baseline (see app/lint-baseline.xml).
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = true
        baseline = file("lint-baseline.xml")
        // targetSdk tracks the Google Play minimum, which lags the newest API
        // level lint knows about, so don't fail builds on OldTargetApi.
        disable += "OldTargetApi"
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.fragment:fragment-ktx:1.9.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("androidx.security:security-crypto:1.1.0")

    testImplementation("junit:junit:4.13.2")
}
