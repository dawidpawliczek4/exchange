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

val pnpmDev by tasks.registering(PnpmTask::class) {
    group = "application"
    description = "Runs the Vite dev server with HMR"
    dependsOn(tasks.pnpmInstall)
    args = listOf("run", "dev")
}

val pnpmBuild by tasks.registering(PnpmTask::class) {
    group = "build"
    description = "Type-checks and bundles the UI into build/dist"
    dependsOn(tasks.pnpmInstall)
    args = listOf("run", "build")
    inputs.files(sources)
    outputs.dir(layout.buildDirectory.dir("dist"))
}

val pnpmTest by tasks.registering(PnpmTask::class) {
    group = "verification"
    description = "Runs the Vitest unit tests once"
    dependsOn(tasks.pnpmInstall)
    args = listOf("run", "test:unit", "--run")
}

val pnpmCheck by tasks.registering(PnpmTask::class) {
    group = "verification"
    description = "Runs oxlint, eslint and the oxfmt format check"
    dependsOn(tasks.pnpmInstall)
    args = listOf("run", "check")
}

tasks.assemble { dependsOn(pnpmBuild) }
tasks.check { dependsOn(pnpmTest, pnpmCheck) }
