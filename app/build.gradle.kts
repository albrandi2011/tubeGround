plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.albrandigames.tubelite"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.albrandigames.tubelite"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.2.2"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.core:core-ktx:1.17.0")

    // Engine: yt-dlp + embedded Python for Android; Media3 handles the media streams directly.
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")

    // Native player/session keeps audio running when the Activity is minimized.
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
}
