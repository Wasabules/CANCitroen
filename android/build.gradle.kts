plugins {
    alias(libs.plugins.android.application) apply false
    // Jamais appliqué (AGP 9 intègre Kotlin) : fixe seulement la version de KGP.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
