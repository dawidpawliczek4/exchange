plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.spotless")
    application
}

dependencies {
    implementation(project(":engine"))
    implementation(libs.kafkaClients)
}

application {
    mainClass = "com.dawidpawliczek.matching.MatchingServiceKt"
}
