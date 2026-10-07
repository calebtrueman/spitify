import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin { jvmToolchain(17) }

/** JavaCPP classifier for the machine doing the build; each installer carries only its own OS's FFmpeg. */
val nativePlatform: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase().let { if (it == "aarch64" || it == "arm64") "arm64" else "x86_64" }
    when {
        os.contains("mac") -> "macosx-$arch"
        os.contains("win") -> "windows-x86_64"
        else -> "linux-$arch"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.swing)
    implementation(libs.json)
    implementation("net.jthink:jaudiotagger:3.0.1")
    implementation("org.bytedeco:javacpp:${libs.versions.javacpp.get()}")
    implementation("org.bytedeco:javacpp:${libs.versions.javacpp.get()}:$nativePlatform")
    implementation("org.bytedeco:ffmpeg:${libs.versions.ffmpeg.get()}")
    implementation("org.bytedeco:ffmpeg:${libs.versions.ffmpeg.get()}:$nativePlatform")
    testImplementation(libs.junit)
    // social deps
    // Same Rust nostr SDK (UniFFI) as the Android app; the JVM jar bundles JNA natives for
    // macOS arm64/x64, Windows x64/arm64, Linux x64/arm64 (glibc + musl) and FreeBSD.
    implementation("org.nostrdevkit:nostr-sdk-jvm:0.45.1")
    implementation("com.google.zxing:core:3.5.3")

    // playback engine deps
    implementation("net.java.dev.jna:jna:5.17.0") // macOS Now Playing (MediaPlayer.framework via the ObjC runtime)
    implementation("com.github.hypfvieh:dbus-java-core:5.1.1") // Linux MPRIS media keys / now playing
    implementation("com.github.hypfvieh:dbus-java-transport-native-unixsocket:5.1.1")

    // ui deps
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.9.6")
}

compose.desktop {
    application {
        mainClass = "com.localfy.app.MainKt"
        jvmArgs += listOf("-Xss2m", "-XX:+UseG1GC", "-Xmx1g")
        // FFmpeg (JavaCPP), JNA and the nostr SDK load classes reflectively from native code; shrinking breaks them.
        buildTypes.release.proguard { isEnabled.set(false) }
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "Spitify"
            packageVersion = "1.0.26"
            description = "Your music, podcasts and audiobooks"
            vendor = "Spitify"
            modules("java.sql", "java.naming", "jdk.crypto.ec", "java.net.http", "jdk.unsupported")
            macOS { bundleID = "com.calebtrueman.spitify.desktop"; iconFile.set(project.file("icons/Spitify.icns")) }
            windows { menuGroup = "Spitify"; upgradeUuid = "8f2b6c4e-3a51-4d7b-9a0e-5c1d2e7f9b13"; perUserInstall = true; iconFile.set(project.file("icons/Spitify.ico")) }
            linux { packageName = "spitify"; iconFile.set(project.file("icons/Spitify.png")) }
        }
    }
}

tasks.test {
    // Full assertion messages in CI logs.
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL; showStandardStreams = false }
}
