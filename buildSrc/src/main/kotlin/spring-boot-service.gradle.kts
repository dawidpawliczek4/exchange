// Convention plugin shared by every Spring Boot microservice in this build.
package buildsrc.convention

import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.kotlin.dsl.getByType

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

kotlin {
    jvmToolchain(25)
}

// Precompiled script plugins don't get the generated `libs` accessor, so we reach the
// version catalog through the API. This keeps libs.versions.toml as the single source of truth.
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    // Versions come from the Spring Boot BOM (via dependency-management), so the catalog entries are version-less.
    "implementation"(libs.findBundle("springBootEcosystem").get())
}

tasks.withType<Test>().configureEach {
    // Configure all test Gradle tasks to use JUnitPlatform.
    useJUnitPlatform()

    // Log information about all test results, not only the failed ones.
    testLogging {
        events(
            TestLogEvent.FAILED,
            TestLogEvent.PASSED,
            TestLogEvent.SKIPPED
        )
    }
}
