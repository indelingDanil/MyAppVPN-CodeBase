plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.myappvpn"
    compileSdk = 36
    // NDK r28+ links native libs with 16 KB page alignment by default
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.myappvpn"
        minSdk = 26
        targetSdk = 34
        // Per-ABI APKs get versionCode * 10 + 1 (armeabi-v7a) / + 2 (arm64-v8a), see androidComponents.
        versionCode = 17
        versionName = "2.2.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Personal tester builds: ./gradlew assembleDebug -PofPresetUrl=… -PofPresetToken=… (empty = none).
        buildConfigField("String", "OF_PRESET_URL", "\"${(project.findProperty("ofPresetUrl") as String?) ?: ""}\"")
        buildConfigField("String", "OF_PRESET_TOKEN", "\"${(project.findProperty("ofPresetToken") as String?) ?: ""}\"")
        vectorDrawables {
            useSupportLibrary = true
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // extractNativeLibs=true in manifest ensures .so files are extracted to nativeLibraryDir
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // One APK per ABI: 64-bit phones and 32-bit Android (e.g. budget Samsung on Android 10).
    // Native executables live in src/main/jniLibs/<abi>/ (xray = libcore.so, tun2socks =
    // libnetstack.so, OpenFlux bridge = libofbridge.so).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }
}

// arm64 gets the higher code, so a 64-bit phone that once got the 32-bit APK can still update.
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.find {
                it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI
            }?.identifier
            val suffix = when (abi) { "armeabi-v7a" -> 1; "arm64-v8a" -> 2; else -> 0 }
            output.versionCode.set((output.versionCode.orNull ?: 0) * 10 + suffix)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.animation)
    implementation(libs.haze)
    implementation(libs.haze.materials)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)

    // DataStore
    implementation(libs.datastore.preferences)

    // Network
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.retrofit.scalars)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Coroutines
    implementation(libs.coroutines.android)

    // Lottie
    implementation(libs.lottie.compose)

    // WorkManager
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // JSON
    implementation(libs.gson)

    debugImplementation(libs.androidx.ui.tooling)
}
