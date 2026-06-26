plugins {
    id("buildsrc.convention.spring-boot-service")
    id("buildsrc.convention.spotless")
}

dependencies {
    implementation(project(":contracts"))
    implementation(libs.springKafka)
}
