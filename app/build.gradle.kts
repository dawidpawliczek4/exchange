plugins {
    id("buildsrc.convention.spring-boot-service")
    id("buildsrc.convention.spotless")
}

dependencies {
    implementation(project(":engine"))
    implementation(libs.springKafka)
}
