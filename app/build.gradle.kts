plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.room")
    id("com.google.gms.google-services")
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
        versionCode = 87
        versionName = "0.15.0.52"

        buildConfigField(
            "String",
            "COOKIT_API_BASE_URL",
            "\"https://cookit.be/api/application-integration/\""
        )
    }

    buildTypes {
        getByName("debug") {
            if (cookitPersistentSigningAvailable) {
                signingConfig = signingConfigs.getByName("cookitPersistent")
            }
        }
        getByName("release") {
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

    // Customer identity: Google Code Scanner UI (QR) without direct camera permission handling.
    implementation("com.google.android.gms:play-services-base:18.11.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    // N1F: native Firebase Cloud Messaging transport.
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-messaging")

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
