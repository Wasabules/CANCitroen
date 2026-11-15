plugins {
    alias(libs.plugins.android.library)
    // Pas de plugin kotlin-android : AGP 9 intègre Kotlin (voir app/build.gradle.kts).
}

// Le contrat entre l'app de l'Atoto et le compagnon : protocole, fichiers, résumé.
// Kotlin pur, sans dépendance : testé en JVM, utilisé tel quel des deux côtés.
android {
    namespace = "com.geoffrey.cancitroen.shared"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}
