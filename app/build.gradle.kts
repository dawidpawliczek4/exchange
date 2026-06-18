plugins {
    // Shared Spring Boot service configuration from buildSrc.
    id("buildsrc.convention.spring-boot-service")
    id("buildsrc.convention.spotless")
}

dependencies {
    // The web service depends on the pure-domain engine library.
    implementation(project(":engine"))
}
