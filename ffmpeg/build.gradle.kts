// Media3's FFmpeg audio decoder (androidx/media @ 1.11.1), compiled from source with FFmpeg 6.0
// static libraries (LGPL build: no --enable-gpl). Adds ALAC, AC-3/E-AC-3, DTS, TrueHD/MLP and
// software fallbacks for FLAC, Opus, Vorbis, MP3, AAC and AMR. The renderer keeps its upstream
// package name because DefaultRenderersFactory loads it by reflection.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk = 37
    ndkVersion = "26.1.10909125"

    defaultConfig {
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(libs.media3.decoder)
    implementation(libs.media3.exoplayer)
    implementation("androidx.annotation:annotation:1.9.1")
    compileOnly("org.checkerframework:checker-qual:3.48.4")
    compileOnly("com.google.errorprone:error_prone_annotations:2.36.0")
}
