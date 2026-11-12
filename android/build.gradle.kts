plugins {
    id("com.android.application") version "8.13.0" apply false
    kotlin("android") version "2.2.20" apply false
    kotlin("jvm") version "2.2.20" apply false
    kotlin("plugin.compose") version "2.2.20" apply false
    kotlin("plugin.serialization") version "2.2.20" apply false
}

subprojects {
    tasks.withType<Test>().configureEach {
        val testHome = layout.buildDirectory.dir("test-home")
        systemProperty("user.home", testHome.get().asFile.absolutePath)
        systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
        doFirst { testHome.get().asFile.mkdirs() }
    }
}

// Pinned formatter/linter, run locally and in CI without an editor plugin.
allprojects {
    val ktlint =
        configurations.create("ktlint") {
            isCanBeConsumed = false
            attributes {
                attribute(org.gradle.api.attributes.Bundling.BUNDLING_ATTRIBUTE, objects.named(org.gradle.api.attributes.Bundling.SHADOWED))
                attribute(org.gradle.api.attributes.Usage.USAGE_ATTRIBUTE, objects.named(org.gradle.api.attributes.Usage.JAVA_RUNTIME))
            }
        }
    dependencies.add(ktlint.name, "com.pinterest.ktlint:ktlint-cli:1.7.1")
    for ((taskName, format) in listOf("ktlintCheck" to false, "ktlintFormat" to true)) {
        tasks.register<JavaExec>(taskName) {
            group = "verification"
            description = if (format) "Format Kotlin sources" else "Check Kotlin formatting"
            classpath = ktlint
            mainClass.set("com.pinterest.ktlint.Main")
            workingDir = rootProject.projectDir
            maxHeapSize = "512m"
            val sources =
                fileTree(projectDir) {
                    include("src/**/*.kt", "*.gradle.kts")
                    if (project == rootProject) include("settings.gradle.kts")
                    exclude("build/**", ".gradle/**")
                }
            if (format) args("--format")
            args("--relative", "--reporter=plain")
            args(sources.files.sortedBy { it.path }.map { it.absolutePath })
        }
    }
}
tasks.named("ktlintCheck") { dependsOn(subprojects.map { it.tasks.named("ktlintCheck") }) }
tasks.named("ktlintFormat") { dependsOn(subprojects.map { it.tasks.named("ktlintFormat") }) }
