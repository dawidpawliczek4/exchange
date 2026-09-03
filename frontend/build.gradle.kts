import com.github.gradle.node.pnpm.task.PnpmTask

plugins {
    base
    alias(libs.plugins.nodeGradle)
}

node {
    download = true
    version = "24.20.0"
    pnpmVersion = "11.25.0"
}

val sources = fileTree(layout.projectDirectory) {
    include("src/**", "public/**", "index.html", "env.d.ts")
    include("package.json", "pnpm-lock.yaml")
    include("vite.config.ts", "vitest.config.ts", "eslint.config.ts", "tsconfig*.json")
    include(".oxlintrc.json", ".oxfmtrc.json")
}

val pnpmBuild by tasks.registering(PnpmTask::class) {
    dependsOn(tasks.pnpmInstall)
    args = listOf("run", "build")
    inputs.files(sources)
    outputs.dir(layout.buildDirectory.dir("dist"))
}

val pnpmTest by tasks.registering(PnpmTask::class) {
    dependsOn(tasks.pnpmInstall)
    args = listOf("run", "test:unit", "--run")
    inputs.files(sources)
    outputs.upToDateWhen { false }
}

val pnpmCheck by tasks.registering(PnpmTask::class) {
    dependsOn(tasks.pnpmInstall)
    args = listOf("run", "check")
    inputs.files(sources)
    outputs.upToDateWhen { false }
}

tasks.assemble { dependsOn(pnpmBuild) }
tasks.check { dependsOn(pnpmTest, pnpmCheck) }
