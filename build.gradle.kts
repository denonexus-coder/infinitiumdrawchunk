object Constants {
    // Mesmas versoes do fork do Sodium (comprovadas em CI) para build reproduzivel.
    const val MINECRAFT_VERSION: String = "1.20.1"
    const val YARN_VERSION: String = "1.20.1+build.10"
    const val FABRIC_LOADER_VERSION: String = "0.16.10"
    const val LWJGL_VERSION: String = "3.3.3"
    const val MOD_VERSION: String = "0.1.0"
}

plugins {
    id("fabric-loom") version "1.9.2"
}

base {
    archivesName = "infinitiumdrawchunk"
    group = "com.denonexus"
    version = Constants.MOD_VERSION
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:${Constants.MINECRAFT_VERSION}")
    mappings("net.fabricmc:yarn:${Constants.YARN_VERSION}:v2")
    modImplementation("net.fabricmc:fabric-loader:${Constants.FABRIC_LOADER_VERSION}")

    // LWJGL: o nucleo do merge (MemoryUtil / buffers alinhados) roda fora do Minecraft
    // no teste unitario, entao precisa das classes e dos natives no classpath de teste.
    testImplementation("org.lwjgl:lwjgl:${Constants.LWJGL_VERSION}")
    testRuntimeOnly("org.lwjgl:lwjgl:${Constants.LWJGL_VERSION}:natives-linux")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

tasks.processResources {
    val props = mapOf("version" to Constants.MOD_VERSION)
    inputs.properties(props)
    filesMatching("fabric.mod.json") {
        expand(props)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
