plugins {
    id("buildsrc.convention.spring-boot-service")
    id("buildsrc.convention.spotless")
}

dependencies {
    implementation(kotlin("reflect"))
    implementation(project(":contracts"))
    implementation(libs.springKafka)
    implementation(libs.springBootStarterSecurity)
    implementation(libs.springBootStarterDataJpa)
    implementation(libs.springBootStarterValidation)
    implementation(libs.springBootFlyway)
    implementation(libs.flywayCore)
    runtimeOnly(libs.flywayPostgresql)
    implementation(libs.jjwtApi)
    runtimeOnly(libs.jjwtImpl)
    runtimeOnly(libs.jjwtJackson)
    runtimeOnly(libs.postgresql)
    testImplementation(kotlin("test"))
    testImplementation(libs.springBootTestcontainers)
    testImplementation(libs.testcontainersJunit)
    testImplementation(libs.testcontainersPostgresql)
}
