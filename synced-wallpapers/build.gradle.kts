plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 compiles Kotlin itself ("built-in Kotlin"); these put the matching Kotlin
    // Gradle plugin version on the classpath for the Compose and serialization compilers.
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
}
