plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Platform-independent Spitify code shared by the Android app and Spitify Desktop:
// models, online music (Monochrome + fallbacks), matching, the taste engine, lyrics and
// playlist import. No Android APIs here.
kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.coroutines.core)
    // Android ships org.json in the platform; Desktop adds the same library at runtime.
    compileOnly(libs.json)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
