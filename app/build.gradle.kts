plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Compose Compiler Gradle Plugin — only available from Kotlin 2.0.0+.
    // Version is inherited from the root plugins block (2.1.21).
    // With this plugin applied, kotlinCompilerExtensionVersion in composeOptions
    // is NOT required and must NOT be set — the plugin manages it automatically.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.pokerpgplayer.app"
    // compileSdk 36 (Android 16) — per Decision Journal / Master Task
    // "Track 2026 target SDK requirement; start with API 35+ and prepare for
    // API 36". AGP 8.10.1 requires compileSdk >= 35 for API 36 targets;
    // compileSdk = 36 satisfies this with no additional configuration.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pokerpgplayer.app"
        // minSdk 26 (Android 8.0) — placeholder, revisit when Device Doctor
        // real device-tier data is available (Runtime Research v1.0 / Device
        // Doctor Phase 1 tasks).
        minSdk = 26
        targetSdk = 36
        versionCode = 67
        versionName = "0.0.67"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }


    signingConfigs {
        getByName("debug") {
            storeFile = file("../keystores/pokerpg-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        // Note: do NOT set composeOptions { kotlinCompilerExtensionVersion }
        // when using org.jetbrains.kotlin.plugin.compose. The plugin resolves
        // the correct Compose compiler version automatically. Setting it
        // explicitly here would conflict with the plugin and cause a build error.
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // kotlinOptions.jvmTarget is the Kotlin counterpart to compileOptions above.
    // Must match — mismatch produces a Kotlin/JVM target compatibility warning
    // (and errors in strict mode).
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Compose BOM 2025.05.01 — latest stable BOM as of the Kotlin 2.1.x era.
    // The BOM manages all androidx.compose.* versions so individual artifacts
    // listed below do not need their own version numbers.
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // AndroidX core
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Lifecycle + ViewModel (MVVM-ready)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")

    // Activity (required for setContent / enableEdgeToEdge)
    implementation("androidx.activity:activity-compose:1.10.1")

    // Compose — versions managed by BOM above
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation — Compose single-Activity graph
    implementation("androidx.navigation:navigation-compose:2.9.0")

    // Sprint 2: DocumentFile tree access for SAF-based folder scanning
    // (GameDetectionService). Note: this artifact moves very slowly —
    // 1.0.1 has been the current stable release for a long time as of
    // Claude's reliable knowledge; confirm nothing newer exists before relying
    // on this as final.
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
