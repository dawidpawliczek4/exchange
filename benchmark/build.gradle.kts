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
}

jmh {
    jmhVersion = "1.37"
    warmupIterations = 3
    iterations = 5
    fork = 1
}
