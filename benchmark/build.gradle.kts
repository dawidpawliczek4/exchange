plugins {
    java
    id("me.champeau.jmh") version "0.7.3"
    id("buildsrc.convention.spotless")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":contracts"))
    implementation(libs.hdrhistogram)
    jmhAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

jmh {
    jmhVersion = "1.37"
    duplicateClassesStrategy = DuplicatesStrategy.EXCLUDE
    resultFormat = "JSON"
    jvmArgs.set(listOf("-Dresults.dir=${layout.projectDirectory.dir("results").asFile.absolutePath}"))
}
