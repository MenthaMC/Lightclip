plugins {
    java
    id("com.gradleup.shadow") version "9.1.0"
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
    maven("https://repo.spongepowered.org/maven/")
    maven("https://repo.leavesmc.org/releases/")
    maven("https://repo.leavesmc.org/snapshots/")
    maven("https://repo.menthamc.org/repository/maven-public/")
}

dependencies {
    implementation("io.sigpipe:jbsdiff:1.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.leavesmc:leaves-plugin-mixin-condition:1.0.0")
    implementation("io.github.llamalad7:mixinextras-common:0.4.1")
    implementation("net.fabricmc:access-widener:2.1.0")
    implementation("net.fabricmc:sponge-mixin:0.16.4+mixin.0.8.7") {
        exclude(group = "com.google.code.gson", module = "gson")
        exclude(group = "com.google.guava", module = "guava")
    }
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("org.jetbrains:annotations:15.0")
}

tasks.shadowJar {
    val prefix = "lightclip.libs"
    listOf(
        "org.apache",
        "org.tukaani",
        "io.sigpipe",
        "com.google",
        "org.objectweb.asm",
        "org.spongepowered",
        "org.leavesmc",
        "org.jetbrains",
        "org.intellij",
        "net.fabricmc",
        "io.leangen",
        "com.llamalad7"
    ).forEach { pack ->
        relocate(pack, "$prefix.$pack")
    }

    exclude("META-INF/LICENSE.txt")
    exclude("META-INF/NOTICE.txt")
}
