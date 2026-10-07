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

    // ui deps
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.9.6")
}

compose.desktop {
    application {
        mainClass = "com.localfy.app.MainKt"
        jvmArgs += listOf("-Xss2m", "-XX:+UseG1GC", "-Xmx1g")
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "Spitify"
            packageVersion = "1.0.24"
            description = "Your music, podcasts and audiobooks"
            vendor = "Spitify"
            modules("java.sql", "java.naming", "jdk.crypto.ec", "java.net.http", "jdk.unsupported")
            macOS { bundleID = "com.calebtrueman.spitify.desktop"; iconFile.set(project.file("icons/Spitify.icns")) }
            windows { menuGroup = "Spitify"; upgradeUuid = "8f2b6c4e-3a51-4d7b-9a0e-5c1d2e7f9b13"; perUserInstall = true; iconFile.set(project.file("icons/Spitify.ico")) }
            linux { packageName = "spitify"; iconFile.set(project.file("icons/Spitify.png")) }
        }
    }
}

