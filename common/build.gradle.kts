plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

java {
    // Bytecode must be consumable by the Android apps (Java 17).
    targetCompatibility = JavaVersion.VERSION_17
    sourceCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.gson)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    // docs/test-vectors.json is the single source of interoperability truth shared with Python.
    systemProperty("vectors.path", rootProject.file("docs/test-vectors.json").absolutePath)
    testLogging { events("failed", "skipped"); showStandardStreams = false }
}
