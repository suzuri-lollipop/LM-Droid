plugins {
    alias(libs.plugins.android.application) apply false
    // org.jetbrains.kotlin.android is deliberately NOT declared: AGP 9's built-in Kotlin support
    // (enabled by default) replaces it, and actually errors if it's also applied.
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

