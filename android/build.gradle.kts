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
