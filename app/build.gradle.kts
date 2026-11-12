plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.legacy.kapt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    // কোডের ইমপোর্ট ও ফোল্ডার স্ট্রাকচার অক্ষত রাখার জন্য namespace আগেরটাই থাকবে
    namespace = "com.awd.teledrive"
    compileSdk = 37

    signingConfigs {
        // Debug builds intentionally use the committed fixed-debug.keystore
        // so debug APKs keep a stable signature across machines and CI runs
        // (existing debug installs keep updating in place). This is a DEBUG
        // keystore with the public default password: it must never be used
        // for release signing (see the release signing config below).
        getByName("debug") {
            val rootKeystore = rootProject.file("fixed-debug.keystore")
            val appKeystore = file("fixed-debug.keystore")
            when {
                rootKeystore.exists() -> {
                    storeFile = rootKeystore
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
                appKeystore.exists() -> {
                    storeFile = appKeystore
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
            }
        }

        create("release") {
            // Release signing ONLY from a real release keystore supplied via
            // environment (CI secrets: KEYSTORE_PATH, KEY_STORE_PASSWORD,
            // ALIAS, KEY_PASSWORD). It must never fall back to the debug
            // keystore: the debug keystore is committed to this repo with the
            // public default password, so anything signed with it can be
            // reproduced by anyone. If no release keystore is configured the
            // release build stays unsigned instead of silently shipping a
            // debug-signed APK as a "release".
            val keystorePath = System.getenv("KEYSTORE_PATH")
            if (keystorePath != null && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEY_STORE_PASSWORD")
                keyAlias = System.getenv("ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            } else {
                logger.warn(
                    "No release keystore configured (KEYSTORE_PATH). " +
                        "The release APK will be unsigned; configure a dedicated " +
                        "release keystore before publishing a release."
                )
            }
        }
    }

    defaultConfig {
        // আপনার অ্যাপের নিজস্ব স্বতন্ত্র প্যাকেজ নেম
        applicationId = "com.yasin.mycloud"
        minSdk = 24
        targetSdk = 35
        versionCode = 8
        versionName = "1.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Shipped locales are the default strings (English) and values-in
        // (Indonesian). There is no values-bn, so "bn" must NOT be listed
        // here — resConfigs would strip the Indonesian strings. (P1-20)
        resConfigs("en", "in")

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }
    }

    // One APK per ABI (arm64-v8a + armeabi-v7a): the native .so files
    // (TDLib, libsodium) are per-ABI, so a universal APK ships both sets
    // and is much larger than either split APK. The in-app updater knows
    // the split names (see UpdateRepository.buildCandidateUrls) and the
    // release workflow publishes every APK under
    // app/build/outputs/apk/release/. (P1-20)
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            val releaseSigning = signingConfigs.findByName("release")
            if (releaseSigning != null && releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }

            ndk {
                debugSymbolLevel = "none"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE*"
            excludes += "META-INF/NOTICE*"
            excludes += "META-INF/ASL2.0"
            excludes += "META-INF/*.kotlin_module"
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            // With ABI splits every output is one ABI, so each output needs
            // its own file name — reusing one name for all outputs would
            // make them collide. (P1-20)
            val abi = output.filters.find {
                it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI
            }?.identifier ?: "universal"
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)?.outputFileName?.set(
                "MyCloud-v${android.defaultConfig.versionName}-$abi-${variant.name}.apk"
            )
        }
    }
}

kapt {
    correctErrorTypes = true
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)

    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Telegram and Security
    implementation(libs.tdlibx)
    implementation(libs.lazysodium.android)
    implementation(libs.jna)

    // Media & Image
    implementation(libs.coilCompose)
    implementation(libs.coilVideo)
    implementation(libs.media3Exoplayer)
    implementation(libs.media3Ui)

    // Room & SQLite
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)
    implementation(libs.sqlcipher)

    // Security & Crypto
    implementation(libs.androidx.security.crypto)
    implementation(libs.argon2kt)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.documentfile)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
