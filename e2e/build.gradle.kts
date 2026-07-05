plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.spotless")
}

dependencies {
    testImplementation(platform(libs.springBootBom))
    testImplementation(project(":app"))
    testImplementation(project(":matching-service"))
    testImplementation(project(":contracts"))
    testImplementation(libs.springBootTest)
    testImplementation(libs.springBootTestRest)
    testImplementation(libs.springBootStarterWebsocket)
    testImplementation(libs.springKafka)
    testImplementation(libs.springKafkaTest)
    testImplementation(libs.springBootTestcontainers)
    testImplementation(libs.testcontainersJunit)
    testImplementation(libs.testcontainersPostgresql)
    testImplementation(libs.testcontainersKafka)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
