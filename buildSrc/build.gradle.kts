plugins {
    // The Kotlin DSL plugin provides a convenient way to develop convention plugins.
    // Convention plugins are located in `src/main/kotlin`, with the file extension `.gradle.kts`,
    // and are applied in the project's `build.gradle.kts` files as required.
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    // Add a dependency on the Kotlin Gradle plugin, so that convention plugins can apply it.
    implementation(libs.kotlinGradlePlugin)
    // kotlin("plugin.spring") -> kotlin-allopen, opens Spring-managed classes for proxying.
    implementation(libs.kotlinAllopen)
    // These give the precompiled convention plugins their versions (no `version` allowed in plugins {} there).
    implementation(libs.springBootGradlePlugin)
    implementation(libs.springDependencyManagementPlugin)
}
