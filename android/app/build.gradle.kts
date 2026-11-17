plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.openguard"
    // compileSdk 35 (was 34): CI run #15 failed because credentials 1.6.0 /
    // work 2.10.1 / core 1.15.0 require SDK 35. targetSdk stays 34 for now —
    // Android 15 behavior changes (edge-to-edge, 16KB pages) are a separate
    // step; compileSdk 35 alone unblocks the build.
    compileSdk = 35

    defaultConfig {
        applicationId = "com.openguard"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // AppAuth OAuth redirect scheme: the Drive sign-in redirect URI
        // is com.openguard:/oauth2redirect (see DriveAuth). AppAuth's
        // library manifest declares its redirect receiver activity
        // against this placeholder, so no manifest entry is needed.
        manifestPlaceholders["appAuthRedirectScheme"] = "com.openguard"
    }

    buildTypes {
        release {
            // No minification in MVP: keeps the build simple and avoids
            // R8 rules for Retrofit/MapLibre until a release is cut.
            isMinifyEnabled = false
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
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)

    implementation(libs.play.services.location)
    implementation(libs.maplibre.android.sdk)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.savedstate)

    // Evidence (Lost Mode only): CameraX video recording + AppAuth
    // OAuth for Google Drive uploads. No camera-view: recording runs
    // without a preview.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.appauth)

    // Passkeys: Credential Manager only. The credentials-play-services-auth
    // artifact is for Sign in with Google, NOT passkeys — do not add it.
    implementation(libs.androidx.credentials)
}
