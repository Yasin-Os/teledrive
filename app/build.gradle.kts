plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.legacy.kapt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    // কোডের ইমপোর্ট ঠিক রাখার জন্য namespace আগেরটাই থাকবে
    namespace = "com.awd.teledrive"
    compileSdk = 37

    signingConfigs {
        // চিরস্থায়ী সিগনেচারের জন্য fixed-debug.keystore সরাসরি বাইন্ড করা হলো
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
            val keystorePath = System.getenv("KEYSTORE_PATH")
            val rootKeystore = rootProject.file("fixed-debug.keystore")
            if (keystorePath != null && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEY_STORE_PASSWORD")
                keyAlias = System.getenv("ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            } else if (rootKeystore.exists()) {
                storeFile = rootKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    defaultConfig {
        // আপনার নিজস্ব স্বতন্ত্র প্যাকেজ নেম (মূল অ্যাপের সাথে আর কখনোই সংঘর্ষ হবে না)
        applicationId = "com.yasin.mycloud"
        minSdk = 24
        targetSdk = 35
        versionCode = 8
        versionName = "1.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
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
            val abi = output.filters.find { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }?.identifier ?: "universal"
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)?.outputFileName?.set("awd-teledrive-v${android.defaultConfig.versionName}-$abi-${variant.name}.apk")
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
