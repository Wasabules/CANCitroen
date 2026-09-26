plugins {
    alias(libs.plugins.android.application)
    // Pas de plugin kotlin-android : AGP 9 intègre Kotlin (la version de KGP
    // est fixée par la déclaration `apply false` du build racine).
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.geoffrey.cancitroen"
    compileSdk = 37          // exigé par androidx.core 1.19 ; sans effet sur l'Atoto (Android 10)

    defaultConfig {
        applicationId = "com.geoffrey.cancitroen"
        minSdk = 29              // Android 10 (Atoto A6PF tourne >= 10)
        targetSdk = 36
        versionCode = 25
        versionName = "0.1.25"
    }

    // Clé de signature LOCALE, non versionnée (android/keystore/, voir
    // docs/app/developpement.md) : copie de la debug.keystore qui a signé
    // toutes les versions installées sur l'Atoto. En changer imposerait de
    // désinstaller l'app (→ perte de l'historique et des réglages).
    // Absente (clone du dépôt) : signature debug standard d'Android.
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
        // Build à installer sur l'Atoto : R8 + ressources réduites, non
        // debuggable (ART et Compose bien plus rapides qu'en debug).
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = appSigning
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    testOptions {
        unitTests.all {
            // Trames de référence partagées avec les tests Python (scripts/).
            // Déclarées en entrée : sinon Gradle ne relance pas les tests quand
            // seul le fichier de fixtures change.
            val fixtures = rootProject.file("../fixtures")
            it.systemProperty("fixturesDir", fixtures.absolutePath)
            it.inputs.dir(fixtures)
        }
        // SlcanParser appelle android.util.Log : stubs qui ne lèvent pas en JVM.
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Schéma Room exporté (app/schemas/) : indispensable pour écrire des
// migrations (@AutoMigration) au lieu d'effacer l'historique. Le plugin gère
// l'export par variante (l'argument KSP seul faisait collisionner debug et
// release quand ils compilent en parallèle).
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.usb.serial)

    // Room (DB locale pour historique véhicule)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
