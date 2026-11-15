plugins {
    alias(libs.plugins.android.application)
    // Pas de plugin kotlin-android : AGP 9 intègre Kotlin (voir app/build.gradle.kts).
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.geoffrey.cancitroen.companion"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.geoffrey.cancitroen.companion"
        minSdk = 29
        targetSdk = 36
        versionCode = 7
        versionName = "0.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Même clé locale que l'app de l'Atoto ; absente (clone du dépôt) : clé debug.
    val cancitroenKeystore = rootProject.file("keystore/cancitroen.keystore")
    signingConfigs {
        if (cancitroenKeystore.exists()) {
            create("cancitroen") {
                storeFile = cancitroenKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }
    val appSigning = signingConfigs.findByName("cancitroen") ?: signingConfigs.getByName("debug")

    buildTypes {
        debug {
            signingConfig = appSigning
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = appSigning
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.car.app)

    testImplementation(libs.junit)
    // Android ne fournit qu'une coquille vide d'org.json aux tests JVM.
    testImplementation(libs.json)

    // Écrans Android Auto construits comme par l'hôte, sur émulateur.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.car.app.testing)
}
