plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tulipskun.aixodia"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tulipskun.aixodia"
        minSdk = 26
        targetSdk = 35
        // CI overwrites both per build (v0.1.<RUN_NUMBER> / versionCode=<RUN_NUMBER>)
        // so every Release installs OVER the previous one: same applicationId +
        // same stable signature + higher versionCode = no uninstall, Room cache kept.
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // Stable key for sideload: every build signs with the SAME key, so
        // updates install over the old APK with data preserved. CI reads the
        // keystore from the AIXODIA_KEYSTORE_B64 repository secret; a local
        // build without it falls back to the default debug key. The key that
        // used to sit in this repository was replaced on 2026-09-25: a public
        // repository must not publish the key that signs its own updates.
        create("stable") {
            val stableKeystore = file("aixodia-debug.keystore")
            if (stableKeystore.exists()) {
                storeFile = stableKeystore
                storeType = "PKCS12"
                storePassword = "aixodiadebug"
                keyAlias = "aixodia-debug"
                keyPassword = "aixodiadebug"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            val stableKeystore = file("aixodia-debug.keystore")
            if (stableKeystore.exists()) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
        release {
            val stableKeystore = file("aixodia-debug.keystore")
            if (stableKeystore.exists()) {
                signingConfig = signingConfigs.getByName("stable")
            }
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom); androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.2")
    ksp("com.squareup.moshi:moshi-kotlin-codegen:1.15.2")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
