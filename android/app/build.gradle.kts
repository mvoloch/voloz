plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.mvoloch.voloz.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.mvoloch.voloz.android"
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "0.2.2"
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("String", "GROQ_ENDPOINT", "\"https://api.groq.com/openai/v1\"")
            buildConfigField("String", "FAKE_MIC_FILE", "\"\"")
        }
        release {
            isMinifyEnabled = false
            // Signed with GB4's debug keystore, like every build the owner has installed,
            // so a release installs over them and keeps the saved keys and settings.
            // A dedicated release key would mean uninstalling first (VOLOZ.md).
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("String", "GROQ_ENDPOINT", "\"https://api.groq.com/openai/v1\"")
            buildConfigField("String", "FAKE_MIC_FILE", "\"\"")
        }
        // Emulator tests only, never sent to a phone: the Groq preset points at a mock
        // server on the host (android/tools/mock_api.py), plain HTTP allowed only to it.
        create("emutest") {
            initWith(getByName("debug"))
            matchingFallbacks += listOf("debug")
            buildConfigField("String", "GROQ_ENDPOINT", "\"http://10.0.2.2:8765/openai/v1\"")
            // The emulator's microphone gives silence here: a raw 16 kHz mono PCM16 file stands in.
            buildConfigField("String", "FAKE_MIC_FILE", "\"/data/data/io.github.mvoloch.voloz.android/files/mic.pcm\"")
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
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

tasks.withType<Test>().configureEach {
    // The Groq end-to-end test reads these; without them it is skipped.
    listOf("GROQ_API_KEY", "VOLOZ_E2E_WAV", "VOLOZ_E2E_OUT").forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
