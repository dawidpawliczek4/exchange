plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(libs.kotlinGradlePlugin)
    implementation(libs.kotlinAllopen)
    implementation(libs.kotlinNoarg)
    implementation(libs.springBootGradlePlugin)
    implementation(libs.springDependencyManagementPlugin)
    implementation(libs.spotlessPlugin)
}
