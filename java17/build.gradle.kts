plugins {
    java
    id("io.github.goooler.shadow") version "8.1.7"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }

    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

repositories {
    mavenCentral()
    maven("https://repo.menthamc.com/repository/maven-releases/")
    maven("https://repo.menthamc.com/repository/maven-snapshots/")
}

dependencies {
    implementation("io.sigpipe:jbsdiff:1.0")
    implementation("com.google.code.gson:gson:2.10.1")
}

tasks.shadowJar {
    val prefix = "lightclip.libs"
    listOf("org.apache", "org.tukaani", "io.sigpipe", "com.google").forEach { pack ->
        relocate(pack, "$prefix.$pack")
    }

    exclude("META-INF/LICENSE.txt")
    exclude("META-INF/NOTICE.txt")
}
