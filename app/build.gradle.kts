plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace   = "com.projectexe"
    compileSdk  = 35

    defaultConfig {
        applicationId   = "com.projectexe"
        minSdk          = 28          // Android 9+ — covers 99% of Revvl 7 users
        targetSdk       = 35
        versionCode     = 1
        versionName     = "0.1.0"

        // NDK ABI filter — arm64-v8a only for Revvl 7 (Snapdragon 6 Gen 1 is 64-bit only)
        ndk { abiFilters += "arm64-v8a" }

        // CMake build for llama.cpp JNI layer
        externalNativeBuild {
            cmake {
                cppFlags  += listOf("-std=c++17", "-O3", "-ffast-math")
                arguments += listOf(
                    // ARM NEON + dotprod + i8mm — all supported by Cortex-A78
                    "-DGGML_NEON=ON",
                    "-DGGML_ARM_DOTPROD=ON",
                    "-DGGML_ARM_I8MM=ON",
                    "-DGGML_FP16_VA=ON",
                    // Disable GPU backends — unstable on Adreno 710 with llama.cpp
                    "-DGGML_VULKAN=OFF",
                    "-DGGML_OPENCL=OFF",
                    "-DGGML_METAL=OFF",
                    // Build config
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DBUILD_SHARED_LIBS=OFF",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path    = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true
        viewBinding = true }

    // Large model files go on external storage — keep APK lean
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)

    // Room — replaces MongoDB for offline persistence
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Async
    implementation(libs.coroutines.android)

    // Preferences / DataStore — soul config persisted here
    implementation(libs.datastore.prefs)

    // JSON serialization for soul export/import
    implementation(libs.gson)

    // Background tasks — soul evolution daemon
    implementation(libs.workmanager.ktx)

    // Navigation
    implementation(libs.navigation.compose)
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
