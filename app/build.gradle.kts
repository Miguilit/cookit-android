plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

val cookitSigningStoreFile = System.getenv("COOKIT_SIGNING_STORE_FILE")?.takeIf { it.isNotBlank() }
val cookitSigningStorePassword = System.getenv("COOKIT_SIGNING_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
val cookitSigningKeyAlias = System.getenv("COOKIT_SIGNING_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val cookitSigningKeyPassword = System.getenv("COOKIT_SIGNING_KEY_PASSWORD")?.takeIf { it.isNotBlank() }

val cookitPersistentSigningAvailable = listOf(
    cookitSigningStoreFile,
    cookitSigningStorePassword,
    cookitSigningKeyAlias,
    cookitSigningKeyPassword,
).all { it != null }

android {
    namespace = "be.cookit.pos.android"
    compileSdk = 37

    signingConfigs {
        create("cookitPersistent") {
            if (cookitPersistentSigningAvailable) {
                storeFile = file(requireNotNull(cookitSigningStoreFile))
                storePassword = requireNotNull(cookitSigningStorePassword)
                keyAlias = requireNotNull(cookitSigningKeyAlias)
                keyPassword = requireNotNull(cookitSigningKeyPassword)
            }
        }
    }

    defaultConfig {
        applicationId = "be.cookit.pos.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 62
        versionName = "0.15.0.27"

        buildConfigField(
            "String",
            "COOKIT_API_BASE_URL",
            "\"https://cookit.be/api/application-integration/\""
        )
    }

    buildTypes {
        getByName("debug") {
            // A14.4.1 embedded Mock FDM is a debug-only test harness. Release builds stay fail-closed.
            buildConfigField("boolean", "ENABLE_MOCK_FDM", "true")
            if (cookitPersistentSigningAvailable) {
                signingConfig = signingConfigs.getByName("cookitPersistent")
            }
        }
        getByName("release") {
            buildConfigField("boolean", "ENABLE_MOCK_FDM", "false")
            if (cookitPersistentSigningAvailable) {
                signingConfig = signingConfigs.getByName("cookitPersistent")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    room {
        schemaDirectory("$projectDir/schemas")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // CookitPad parity: native Star Micronics provider.
    implementation("com.starmicronics:stario10:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.10.2")

    // A14.2A: durable Android fiscal runtime identity on Room / SQLite.
    val roomVersion = "2.8.5"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
