import org.gradle.api.logging.configuration.ShowStacktrace

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    kotlin("plugin.serialization") version "2.0.21"
    id("com.google.dagger.hilt.android")
    alias(libs.plugins.google.gms.google.services)
    alias(libs.plugins.google.firebase.crashlytics)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.viperdam.kidsprayer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.viperdam.kidsprayer"
        minSdk = 29
        targetSdk = 35
        versionCode = 34
        versionName = "34.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Explicitly include common ABIs to ensure native libraries are packaged
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    buildTypes {
        debug {
            // Test ad IDs for debugging
            buildConfigField("String", "ADMOB_APP_ID", "\"ca-app-pub-3940256099942544~3347511713\"")
            buildConfigField("String", "ADMOB_BANNER_ID", "\"ca-app-pub-3940256099942544/6300978111\"")
            buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"ca-app-pub-3940256099942544/1033173712\"")
            buildConfigField("String", "ADMOB_NATIVE_ID", "\"ca-app-pub-3940256099942544/2247696110\"")
            buildConfigField("String", "ADMOB_APP_OPEN_ID", "\"ca-app-pub-3940256099942544/3419835294\"")
            
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            
            // Additional R8/ProGuard configuration
            project.gradle.startParameter.showStacktrace = ShowStacktrace.ALWAYS
            
            // Production ad IDs
            buildConfigField("String", "ADMOB_APP_ID", "\"ca-app-pub-6928555061691394~1746256194\"")
            buildConfigField("String", "ADMOB_BANNER_ID", "\"ca-app-pub-6928555061691394/7584313339\"")
            buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"ca-app-pub-6928555061691394/2468380730\"")
            buildConfigField("String", "ADMOB_NATIVE_ID", "\"ca-app-pub-6928555061691394/6997716907\"")
            buildConfigField("String", "ADMOB_APP_OPEN_ID", "\"ca-app-pub-6928555061691394/6688736896\"")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    
    buildFeatures {
        viewBinding = true
        dataBinding = true
        buildConfig = true
        compose = true
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    
    kotlinOptions {
        jvmTarget = "17"
    }
    
    // Configure KSP for Hilt
    ksp {
        arg("dagger.fastInit", "enabled")
        arg("dagger.hilt.android.internal.disableAndroidSuperclassValidation", "true")
        arg("dagger.hilt.internal.useAggregatingRootProcessor", "true")
    }

    // Enable Hilt aggregating task to fix processor warnings
    hilt {
        enableAggregatingTask = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/license.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/notice.txt"
            excludes += "META-INF/ASL2.0"
            excludes += "META-INF/*.kotlin_module"
            excludes += "META-INF/versions/9/previous-compilation-data.bin"
            
            // Handling duplicate files
            pickFirsts.add("**/*.proto")
            pickFirsts.add("**/*.properties")
            pickFirsts.add("META-INF/services/*")
            pickFirsts.add("META-INF/proguard/*")
            // Add pickFirst for libc++_shared.so based on Rive docs recommendation
            pickFirsts.add("lib/x86/libc++_shared.so")
            pickFirsts.add("lib/x86_64/libc++_shared.so")
            pickFirsts.add("lib/armeabi-v7a/libc++_shared.so")
            pickFirsts.add("lib/arm64-v8a/libc++_shared.so")
        }
        
        jniLibs {
            // Use compressed libraries as MediaPipe's native libs might not be page-aligned
            // Rive might also benefit from this, keep it for now.
            useLegacyPackaging = true 
        }
    }
}

dependencies {
    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation(libs.firebase.crashlytics)
    implementation(libs.transport.runtime)

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.51.1")
    ksp("com.google.dagger:hilt-compiler:2.51.1")
    implementation("androidx.hilt:hilt-work:1.0.0")
    ksp("androidx.hilt:hilt-compiler:1.1.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.work:work-multiprocess:2.9.0")

    // Core Android
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.swiperefreshlayout)
    
    // Activity - Edge-to-Edge Support
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.window:window:1.2.0")
    
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    
    // ViewModel & LiveData
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:${libs.versions.lifecycle.get()}")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:${libs.versions.lifecycle.get()}")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:${libs.versions.lifecycle.get()}")
    implementation("androidx.lifecycle:lifecycle-process:${libs.versions.lifecycle.get()}")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.8.0")
    
    // Location Services
    implementation("com.google.android.gms:play-services-location:21.0.1")
    
    // CameraX
    val cameraxVersion = "1.3.1"
    implementation("androidx.camera:camera-core:${cameraxVersion}")
    implementation("androidx.camera:camera-camera2:${cameraxVersion}")
    implementation("androidx.camera:camera-lifecycle:${cameraxVersion}")
    implementation("androidx.camera:camera-view:${cameraxVersion}")
    implementation("androidx.camera:camera-extensions:${cameraxVersion}")
    
    // Splash Screen
    implementation("androidx.core:core-splashscreen:1.0.0")

    // MediaPipe
    implementation("com.google.mediapipe:tasks-vision:latest.release")
    implementation("androidx.camera:camera-core:1.3.3")

    // AdMob
    implementation("com.google.android.gms:play-services-ads:24.0.0")
    implementation("com.unity3d.ads:unity-ads:4.14.1")
    
    // UMP SDK for GDPR consent
    implementation("com.google.android.ump:user-messaging-platform:2.2.0")
    
    // Prayer Times calculation
    implementation("com.batoulapps.adhan:adhan2:0.0.5")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    
    // Security
    implementation("androidx.security:security-crypto:1.0.0")
    
    // Guava for ListenableFuture
    implementation("com.google.guava:guava:32.1.3-android")
    
    // Play Core for In-App Reviews
    implementation("com.google.android.play:review:2.0.1")
    implementation("com.google.android.play:review-ktx:2.0.1")
    
    // Play Core for In-App Updates
    implementation("com.google.android.play:app-update-ktx:2.1.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Lottie Animation - Updated to the latest version
    implementation("com.airbnb.android:lottie:6.4.0")
    implementation("com.airbnb.android:lottie-compose:6.3.0")

    // Room Database
    val roomVersion = "2.6.1" // Use a recent stable version
    implementation("androidx.room:room-runtime:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion") // For Coroutines support

    // Quran Library (MIT License) - Now using local module
    implementation(project(":HolyQuran"))

    // Lottie for vector animations
    implementation("com.airbnb.android:lottie:6.4.0") // Check for the latest version

    // Rive for interactive vector animations
    implementation("app.rive:rive-android:9.6.5") // Check for the latest version
}
