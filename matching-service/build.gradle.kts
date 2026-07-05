plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.spotless")
    application
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":contracts"))
    implementation(libs.kafkaClients)
    testImplementation(libs.junitJupiter)
    testImplementation(libs.testcontainersJunit)
    testImplementation(libs.testcontainersKafka)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "com.dawidpawliczek.matching.MatchingServiceKt"
}
