import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Version comes from the root build (or core/settings.gradle.kts when built standalone).
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(kotlin("test-junit"))
}

// Desktop CLI used for CI smoke tests: `./gradlew :core:run --args="<spotify link or audio file>"`.
application {
    mainClass = "dev.aichecker.core.cli.MainKt"
}
