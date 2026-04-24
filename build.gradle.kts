import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
    id("com.gradleup.shadow") version "8.3.5"
}

group = "io.github.devpilot"
version = "1.0.0"

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

val apiKey: String = localProperties.getProperty("API_KEY", "")

dependencies {
    implementation(libs.koog.agents)
    implementation(libs.koog.tools)
    implementation(libs.koog.google)
    implementation(libs.koog.ollama)
    implementation(libs.koog.anthropic)
    implementation(libs.koog.openai)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.logging)
    implementation(libs.logback.classic)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("devpilot.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.withType<JavaExec>().configureEach {
    systemProperty("API_KEY", apiKey)
    jvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.withType<org.gradle.api.tasks.bundling.Jar>().named("shadowJar").configure {
    archiveBaseName.set("devpilot")
    archiveClassifier.set("")
    archiveVersion.set("")
    manifest {
        attributes["Main-Class"] = "devpilot.MainKt"
    }
    exclude("META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.SF")
}
