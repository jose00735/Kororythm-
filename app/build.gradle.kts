plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.paparazzi")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.koro.kororythm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.koro.kororythm"
        minSdk = 26
        targetSdk = 35
        versionCode = 18
        versionName = "1.3.0"
        // yt-dlp ships Python/ffmpeg as native libs: phones only (keeps the APK smaller)
    }

    // yt-dlp brings Python + FFmpeg per CPU type: one APK per ABI keeps each download small
    // (arm64-v8a for any phone from the last ~8 years; armeabi-v7a for older 32-bit ones).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key until a release keystore is set up in CI.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    // youtubedl-android runs its bundled binaries from the extracted native-lib folder
    packaging { jniLibs { useLegacyPackaging = true } }
    testOptions {
        unitTests.all { it.maxHeapSize = "2g" }
    }
    lint {
        // Media3's @UnstableApi opt-ins are deliberate (custom audio processors).
        disable += "UnsafeOptInUsageError"
        abortOnError = false
    }
}

dependencies {
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")

    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.glance:glance-appwidget:1.1.1")

    // yt-dlp + Python + ffmpeg for the Downloads screen (GPL-3.0)
    val ytdl = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$ytdl")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$ytdl")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real org.json for JVM tests

}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }
