plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.googleGmsServices)
}

android {
    namespace = "com.fireloc.fireloc"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fireloc.fireloc"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            buildConfigField("boolean", "DEBUG", "true")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/NOTICE.md"
        }
        jniLibs.pickFirsts.addAll(listOf(
            "lib/arm64-v8a/libc++_shared.so",
            "lib/armeabi-v7a/libc++_shared.so"
        ))
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = true
    }
    buildToolsVersion = "34.0.0" // Or your specific version
}

// ========================================================================
//                        DEPENDENCIES BLOCK (UPDATED)
// ========================================================================
dependencies {
    // Define versions (adjust as necessary or get from your libs.versions.toml)
    val coreKtxVersion = "1.13.1"
    val appcompatVersion = "1.7.0"
    val constraintlayoutVersion = "2.1.4"
    val firebaseBomVersion = "33.1.0"
    val playServicesAuthVersion = "21.2.0"
    val playServicesLocationVersion = "21.3.0"
    val retrofitVersion = "2.11.0"
    val retrofitGsonConverterVersion = "2.11.0"
    val okhttpLoggingVersion = "4.12.0"
    val coroutinesVersion = "1.8.1"
    val cameraxVersion = "1.3.3" // This version must be consistent for all CameraX modules
    val lifecycleVersion = "2.8.1"
    val activityKtxVersion = "1.9.0"
    val onnxRuntimeVersion = "1.18.0"
    val junitVersion = "4.13.2"
    val androidxJunitVersion = "1.1.5"
    val espressoVersion = "3.5.1"


    // --- Core AndroidX ---
    implementation("androidx.core:core-ktx:$coreKtxVersion")
    implementation("androidx.appcompat:appcompat:$appcompatVersion")
    implementation("androidx.constraintlayout:constraintlayout:$constraintlayoutVersion")

    // --- Firebase ---
    implementation(platform("com.google.firebase:firebase-bom:$firebaseBomVersion"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")

    // *** NEW/VERIFIED: Firebase App Check (Required by CloudVerificationService.kt) ***
    implementation("com.google.firebase:firebase-appcheck-ktx")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    implementation("com.google.firebase:firebase-appcheck-debug")

    // --- Google Play Services (Required by SensorFusionManager.kt) ---
    implementation("com.google.android.gms:play-services-auth:$playServicesAuthVersion")
    implementation("com.google.android.gms:play-services-location:$playServicesLocationVersion")

    // --- Networking ---
    implementation("com.squareup.retrofit2:retrofit:$retrofitVersion")
    implementation("com.squareup.retrofit2:converter-gson:$retrofitGsonConverterVersion")
    implementation("com.squareup.okhttp3:logging-interceptor:$okhttpLoggingVersion")

    // --- Coroutines (Required by SensorFusionManager.kt and CloudVerificationService.kt for 'await()') ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")
    // This library provides the .await() extension function for Firebase Tasks
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:$coroutinesVersion")

    // --- CameraX (Required for CameraManager.kt and Camera2CameraInfo) ---
    implementation("androidx.camera:camera-core:$cameraxVersion")
    // *** CRITICAL FIX: The interop library needed for Camera2CameraInfo ***
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // --- Lifecycle & Activity ---
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycleVersion")
    implementation("androidx.activity:activity-ktx:$activityKtxVersion")

    // --- ONNX Runtime ---
    implementation("com.microsoft.onnxruntime:onnxruntime-android:$onnxRuntimeVersion")

    // --- Testing ---
    testImplementation("junit:junit:$junitVersion")
    androidTestImplementation("androidx.test.ext:junit:$androidxJunitVersion")
    androidTestImplementation("androidx.test.espresso:espresso-core:$espressoVersion")
}